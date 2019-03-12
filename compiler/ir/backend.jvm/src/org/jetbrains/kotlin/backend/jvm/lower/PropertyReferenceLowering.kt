/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal val propertyReferencePhase = makeIrFilePhase(
    ::PropertyReferenceLowering,
    name = "PropertyReference",
    description = "Construct KProperty instances returned by expressions such as A::x and A()::x"
)

internal class PropertyReferenceLowering(val context: JvmBackendContext) : ClassLoweringPass {
    // TODO: join IrLocalDelegatedPropertyReference and IrPropertyReference via the class hierarchy?
    private val IrMemberAccessExpression.getter: IrFunctionSymbol?
        get() = (this as? IrPropertyReference)?.getter ?: (this as? IrLocalDelegatedPropertyReference)?.getter

    private val IrMemberAccessExpression.setter: IrFunctionSymbol?
        get() = (this as? IrPropertyReference)?.setter ?: (this as? IrLocalDelegatedPropertyReference)?.setter

    private val IrMemberAccessExpression.field: IrFieldSymbol?
        get() = (this as? IrPropertyReference)?.field

    private val IrMemberAccessExpression.signature: String
        // Plain Java fields do not have a getter, but can be referenced nonetheless.
        get() = getter?.owner?.let { context.state.typeMapper.mapSignatureSkipGeneric(it.descriptor).toString() }
            ?: TODO("plain Java field signature")

    private val IrMemberAccessExpression.symbol: IrSymbol
        get() = getter?.owner?.symbol ?: field!!.owner.symbol

    private fun IrFunction.visibleFrom(scope: Scope): Boolean =
        // Local declarations (delegated property accessors in this case) have LOCAL visibility; so long as the property itself
        // is visible, then so is the accessor.
        visibility == Visibilities.LOCAL || visibility.isVisible(Visibilities.ALWAYS_SUITABLE_RECEIVER, descriptor, scope.scopeOwner)

    private val plainJavaClass =
        context.getIrClass(FqName("java.lang.Class")).owner

    private val reflectionClass =
        context.getIrClass(FqName("kotlin.jvm.internal.Reflection")).owner

    private val getOrCreateKotlinClass =
        reflectionClass.functions.single { it.name == Name.identifier("getOrCreateKotlinClass") && it.valueParameters.size == 1 }

    private val getOrCreateKotlinPackage =
        reflectionClass.functions.single { it.name == Name.identifier("getOrCreateKotlinPackage") && it.valueParameters.size == 2 }

    private val arrayItemGetter =
        context.ir.symbols.array.owner.functions.single { it.name == Name.identifier("get") }

    private val kPropertyType =
        context.reflectionTypes.kProperty.toIrType()

    private val kPropertiesFieldType =
        context.ir.symbols.array.createType(false, listOf(makeTypeProjection(kPropertyType, Variance.OUT_VARIANCE)))

    // TODO: remove code duplication with CallableReferenceLowering
    private val IrMemberAccessExpression.parentJavaClassReference
        get() = IrClassReferenceImpl(
            startOffset, endOffset,
            plainJavaClass.defaultType,
            plainJavaClass.symbol,
            CrIrType(context.state.typeMapper.mapImplementationOwner(descriptor))
        )

    private fun IrBuilderWithScope.buildReflectedContainerReference(expression: IrMemberAccessExpression): IrExpression {
        val parent = expression.getter?.owner?.parent ?: expression.field?.owner?.parent
        return when (parent) {
            is IrPackageFragment -> irCall(getOrCreateKotlinPackage).apply {
                putValueArgument(0, expression.parentJavaClassReference)
                putValueArgument(1, irString(this@PropertyReferenceLowering.context.state.moduleName))
            }
            is IrClass -> irCall(getOrCreateKotlinClass).apply {
                putValueArgument(0, expression.parentJavaClassReference)
            }
            else -> irNull()
        }
    }

    private class PropertyReferenceKind(
        val interfaceSymbol: IrClassSymbol,
        val reflectedSymbol: IrClassSymbol,
        val wrapper: IrFunction
    )

    private fun propertyReferenceKind(mutable: Boolean, i: Int) = PropertyReferenceKind(
        context.getIrClass(FqName("kotlin.jvm.internal.${if (mutable) "Mutable" else ""}PropertyReference$i")),
        context.getIrClass(FqName("kotlin.jvm.internal.${if (mutable) "Mutable" else ""}PropertyReference${i}Impl")),
        reflectionClass.functions.single { it.name == Name.identifier(if (mutable) "mutableProperty$i" else "property$i") }
    )

    private fun propertyReferenceKindFor(expression: IrMemberAccessExpression, scope: Scope): PropertyReferenceKind =
        expression.getter?.owner?.let {
            val boundReceivers = listOfNotNull(expression.dispatchReceiver, expression.extensionReceiver).size
            val needReceivers = listOfNotNull(it.dispatchReceiverParameter, it.extensionReceiverParameter).size
            if (boundReceivers == 2 || (expression.extensionReceiver != null && needReceivers == 2))
                // PropertyReference1 will swap the receivers if bound with the extension one, and PropertyReference0
                // has no way to bind two receivers at once.
                TODO("property reference with 2 receivers")
            propertyReferenceKind(expression.setter?.owner?.visibleFrom(scope) ?: false, needReceivers - boundReceivers)
        } ?: expression.field?.owner?.let {
            propertyReferenceKind(!it.isFinal, if (it.isStatic || expression.dispatchReceiver != null) 0 else 1)
        } ?: throw AssertionError("property has no getter and no field")

    private data class PropertyCacheKey(val symbol: IrSymbol, val reflected: Boolean)
    private data class PropertyClassCacheKey(val symbol: IrSymbol, val boundReceiver: Boolean)
    private data class PropertyInstance(val initializer: IrExpression, val index: Int)

    override fun lower(irClass: IrClass) {
        val kProperties = mutableMapOf<PropertyCacheKey, PropertyInstance>()
        val kPropertyClasses = mutableMapOf<PropertyClassCacheKey, IrClass>()
        val kPropertiesField = buildField {
            name = Name.identifier(JvmAbi.DELEGATED_PROPERTIES_ARRAY_NAME)
            type = kPropertiesFieldType
            origin = JvmLoweredDeclarationOrigin.GENERATED_PROPERTY_REFERENCE
            isFinal = true
            isStatic = true
        }

        irClass.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitPropertyReference(expression: IrPropertyReference): IrExpression =
                cachedKProperty(expression)

            override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference): IrExpression =
                cachedKProperty(expression)

            private fun cachedKProperty(expression: IrMemberAccessExpression): IrExpression {
                if (expression.dispatchReceiver != null || expression.extensionReceiver != null)
                    // Reflected implementation does not support partial application; also, cannot cache instances with arguments.
                    return createSpecializedKProperty(expression)

                // For delegated properties, the getter and setter contain a reference each as the second argument to getValue
                // and setValue. Since it's highly unlikely that anyone will call get/set on these, optimize for space.
                val useReflectedImpl = expression.origin == IrStatementOrigin.PROPERTY_REFERENCE_FOR_DELEGATE
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).run {
                    val (_, index) = kProperties.getOrPut(PropertyCacheKey(expression.symbol, useReflectedImpl)) {
                        val kProperty = if (useReflectedImpl)
                            createReflectedKProperty(expression)
                        else
                            createSpecializedKProperty(expression)
                        PropertyInstance(kProperty, kProperties.size)
                    }
                    irCall(arrayItemGetter).apply {
                        dispatchReceiver = irGetField(null, kPropertiesField)
                        putValueArgument(0, irInt(index))
                    }
                }
            }

            // Create an instance of KProperty that uses Java reflection to locate the getter and the setter. This kind of reference
            // does not support local variables or bound receivers (e.g. `Class().field`) and is slower, but takes up less space.
            private fun createReflectedKProperty(expression: IrMemberAccessExpression): IrExpression {
                val referenceKind = propertyReferenceKindFor(expression, currentScope!!.scope)
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).run {
                    irCall(referenceKind.wrapper).apply {
                        putValueArgument(0, irCall(referenceKind.reflectedSymbol.constructors.single()).apply {
                            putValueArgument(0, buildReflectedContainerReference(expression))
                            putValueArgument(1, irString(expression.descriptor.name.asString()))
                            putValueArgument(2, irString(expression.signature))
                        })
                    }
                }
            }

            // Create an instance of KProperty that overrides the get() and set() method to directly call getX() and setX() on the object.
            // This is (relatively) fast, but space-inefficient. Also, the instances can store bound receivers in their fields.
            private fun createSpecializedKProperty(expression: IrMemberAccessExpression): IrExpression {
                val bound = expression.dispatchReceiver != null || expression.extensionReceiver != null
                val referenceClass = kPropertyClasses.getOrPut(PropertyClassCacheKey(expression.symbol, bound)) {
                    createKPropertySubclass(expression)
                }
                return context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).run {
                    irCall(referenceClass.constructors.single()).apply {
                        var index = 0
                        expression.dispatchReceiver?.let { putValueArgument(index++, it) }
                        expression.extensionReceiver?.let { putValueArgument(index++, it) }
                    }
                }
            }

            private fun createKPropertySubclass(expression: IrMemberAccessExpression): IrClass {
                val superClass = propertyReferenceKindFor(expression, currentScope!!.scope).interfaceSymbol.owner
                val referenceClass = buildClass {
                    setSourceRange(expression)
                    name = Name.identifier("${currentScope!!.scope.scopeOwner.name}\$${expression.descriptor.name}\$${kPropertyClasses.size}")
                    origin = JvmLoweredDeclarationOrigin.GENERATED_PROPERTY_REFERENCE
                }.apply {
                    parent = irClass
                    superTypes += IrSimpleTypeImpl(superClass.symbol, false, listOf(), listOf())
                    createImplicitParameterDeclarationWithWrappedDescriptor()
                }
                val referenceThis = referenceClass.thisReceiver!!

                buildConstructor {
                    setSourceRange(expression)
                    returnType = referenceClass.defaultType
                    origin = referenceClass.origin
                    isPrimary = true
                }.apply {
                    parent = referenceClass
                    referenceClass.declarations.add(this)

                    val constructor = this
                    // See propertyReferenceKindFor -- only one of them could ever be present.
                    val parameter = (expression.dispatchReceiver ?: expression.extensionReceiver)?.let {
                        buildValueParameter {
                            setSourceRange(expression)
                            name = Name.identifier("receiver")
                            type = it.type
                            index = valueParameters.size
                            origin = referenceClass.origin
                        }.apply {
                            parent = constructor
                            valueParameters.add(this)
                        }
                    }
                    body = context.createIrBuilder(symbol).irBlockBody(startOffset, endOffset) {
                        val superArgs = if (parameter == null) 0 else 1
                        +irDelegatingConstructorCall(superClass.constructors.single { it.valueParameters.size == superArgs }).apply {
                            parameter?.let { putValueArgument(0, irGet(parameter)) }
                        }
                        +IrInstanceInitializerCallImpl(startOffset, endOffset, referenceClass.symbol, context.irBuiltIns.unitType)
                    }
                }

                fun buildOverride(method: IrSimpleFunction, build: IrBlockBodyBuilder.(List<IrValueParameter>) -> IrExpression) =
                    buildFun {
                        setSourceRange(expression)
                        name = if (method.name == Name.special("<get-name>")) Name.identifier("getName") else method.name
                        returnType = method.returnType
                        visibility = method.visibility
                        origin = referenceClass.origin
                    }.apply {
                        parent = referenceClass
                        referenceClass.declarations.add(this)

                        overriddenSymbols.add(method.symbol)
                        dispatchReceiverParameter = referenceThis.copyTo(this)
                        for (parameter in method.valueParameters)
                            valueParameters.add(parameter.copyTo(this))
                        body = context.createIrBuilder(symbol).irBlockBody(startOffset, endOffset) {
                            +irReturn(build(valueParameters))
                        }
                    }

                val receiverField = superClass.properties.single { it.name == Name.identifier("receiver") }.backingField!!
                fun IrBuilderWithScope.setReceiversOn(call: IrCall, valueParameters: List<IrValueParameter>) {
                    var index = 0
                    call.dispatchReceiver = call.symbol.owner.dispatchReceiverParameter?.let {
                        if (expression.dispatchReceiver != null)
                            irGetField(irGet(referenceThis), receiverField)
                        else
                            irImplicitCast(irGet(valueParameters[index++]), it.type)
                    }
                    call.extensionReceiver = call.symbol.owner.extensionReceiverParameter?.let {
                        if (expression.extensionReceiver != null)
                            irGetField(irGet(referenceThis), receiverField)
                        else
                            irImplicitCast(irGet(valueParameters[index++]), it.type)
                    }
                }

                buildOverride(superClass.properties.single { it.name == Name.identifier("name") }.getter!!) {
                    irString(expression.descriptor.name.asString())
                }

                buildOverride(superClass.functions.single { it.name == Name.identifier("getOwner") }) {
                    buildReflectedContainerReference(expression)
                }

                buildOverride(superClass.functions.single { it.name == Name.identifier("getSignature") }) {
                    irString(expression.signature)
                }

                expression.getter?.owner?.let { getter ->
                    buildOverride(superClass.functions.single { it.name == Name.identifier("get") }) { valueParameters ->
                        irGet(getter.returnType, null, getter.symbol).apply { setReceiversOn(this, valueParameters) }
                    }
                }

                expression.setter?.owner?.let { setter ->
                    if (setter.visibleFrom(currentScope!!.scope)) {
                        buildOverride(superClass.functions.single { it.name == Name.identifier("set") }) { valueParameters ->
                            val value = irGet(valueParameters.last())
                            irSet(setter.returnType, null, setter.symbol, value).apply { setReceiversOn(this, valueParameters) }
                        }
                    }
                }

                // TODO plain Java properties do not have accessors, should generate direct field read/write instead
                // expression.field?.owner?.let { ... }
                return referenceClass
            }
        })

        // Put new field at the beginning so that static delegated properties with initializers work correctly.
        if (kProperties.isNotEmpty()) {
            irClass.declarations.add(0, kPropertiesField.apply {
                parent = irClass
                initializer = context.createIrBuilder(irClass.symbol).run {
                    val initializers = kProperties.values.sortedBy { it.index }.map { it.initializer }
                    irExprBody(irCall(this@PropertyReferenceLowering.context.ir.symbols.arrayOf).apply {
                        putValueArgument(0, IrVarargImpl(startOffset, endOffset, kPropertiesFieldType, kPropertyType, initializers))
                    })
                }
            })
        }
        irClass.declarations.addAll(0, kPropertyClasses.values)
    }
}
