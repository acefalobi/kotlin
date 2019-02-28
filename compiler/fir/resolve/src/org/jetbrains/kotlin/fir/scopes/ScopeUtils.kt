/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.expandedConeType
import org.jetbrains.kotlin.fir.declarations.superConeTypes
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeAbbreviatedType
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeAbbreviatedTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl


fun lookupSuperTypes(
    klass: FirRegularClass,
    lookupInterfaces: Boolean,
    deep: Boolean,
    useSiteSession: FirSession = klass.session
): List<ConeClassLikeType> {
    return mutableListOf<ConeClassLikeType>().also {
        if (lookupInterfaces) klass.symbol.collectSuperTypes(useSiteSession, it, deep)
        else klass.symbol.collectSuperClasses(useSiteSession, it)
    }
}

private fun ConeClassLikeType.projection(useSiteSession: FirSession): ConeClassLikeType {
    if (this is ConeClassErrorType) return this
    val symbolProvider = FirSymbolProvider.getInstance(useSiteSession)
    val declaredSymbol = symbol
    if (declaredSymbol is FirBasedSymbol<*> && declaredSymbol.fir.session.moduleInfo == useSiteSession.moduleInfo) {
        return this
    }
    val useSiteSymbol = symbolProvider.getClassLikeSymbolByFqName(declaredSymbol.classId)
    return if (useSiteSymbol !is ConeClassLikeSymbol || useSiteSymbol == declaredSymbol) {
        this
    } else when (this) {
        is ConeClassTypeImpl ->
            ConeClassTypeImpl(useSiteSymbol, typeArguments, nullability.isNullable)
        is ConeAbbreviatedTypeImpl ->
            ConeAbbreviatedTypeImpl(useSiteSymbol, typeArguments, directExpansion, nullability.isNullable)
        else ->
            this
    }
}

private tailrec fun ConeClassLikeType.computePartialExpansion(): ConeClassLikeType? {
    return when (this) {
        is ConeAbbreviatedType -> directExpansion.takeIf { it !is ConeClassErrorType }?.computePartialExpansion()
        else -> return this
    }
}

private tailrec fun ConeClassLikeSymbol.collectSuperClasses(useSiteSession: FirSession, list: MutableList<ConeClassLikeType>) {
    when (this) {
        is FirClassSymbol -> {
            val superClassType =
                fir.superConeTypes
                    .map { it.projection(useSiteSession).computePartialExpansion() }
                    .firstOrNull {
                        it !is ConeClassErrorType && (it?.symbol as? FirClassSymbol)?.fir?.classKind == ClassKind.CLASS
                    } ?: return
            list += superClassType
            superClassType.symbol.collectSuperClasses(useSiteSession, list)
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.projection(useSiteSession)?.computePartialExpansion() ?: return
            expansion.symbol.collectSuperClasses(useSiteSession, list)
        }
        else -> error("?!id:1")
    }
}

private fun ConeClassLikeSymbol.collectSuperTypes(useSiteSession: FirSession, list: MutableList<ConeClassLikeType>, deep: Boolean) {
    when (this) {
        is FirClassSymbol -> {
            val superClassTypes =
                fir.superConeTypes.mapNotNull { it.projection(useSiteSession).computePartialExpansion() }
            list += superClassTypes
            if (deep)
                superClassTypes.forEach {
                    if (it !is ConeClassErrorType) {
                        it.symbol.collectSuperTypes(useSiteSession, list, deep)
                    }
                }
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.projection(useSiteSession)?.computePartialExpansion() ?: return
            expansion.symbol.collectSuperTypes(useSiteSession, list, deep)
        }
        else -> error("?!id:1")
    }
}