package org.openrewrite.kotlin;

import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.FirSession;
import org.jetbrains.kotlin.fir.declarations.*;
import org.jetbrains.kotlin.fir.symbols.impl.*;
import org.jetbrains.kotlin.fir.types.*;
import org.jetbrains.kotlin.fir.types.impl.FirImplicitNullableAnyTypeRef;
import org.jetbrains.kotlin.name.ClassId;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaTypeSignatureBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

public class KotlinTypeSignatureBuilder implements JavaTypeSignatureBuilder {

    private final FirSession firSession;

    @Nullable
    Set<String> typeVariableNameStack;

    public KotlinTypeSignatureBuilder(FirSession firSession) {
        this.firSession = firSession;
    }

    @Override
    public String signature(@Nullable Object t) {
        return signature((FirElement) t);
    }
    // this may be helpful later.
//            ConeTypeUtilsKt.getClassId(coneType);

    private String signature(@Nullable FirElement type) {
        if (type == null) {
            return "{undefined}";
        } else if (type instanceof FirClass) {
            return ((FirClass) type).getTypeParameters().size() > 0 ? parameterizedSignature(type) : classSignature(type);
        } else if (type instanceof FirResolvedTypeRef) {
            return resolvedTypeRef(type);
        } else if (type instanceof FirValueParameter) {
            return signature(((FirValueParameter) type).getReturnTypeRef());
        } else if (type instanceof FirTypeParameter) {
            return genericSignature(type);
        }

        throw new IllegalStateException("Unexpected type " + type.getClass().getName());
    }

    @Override
    public String arraySignature(Object type) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public String classSignature(@Nullable Object type) {
        if (type == null) {
            return "{undefined}";
        }

        FirClassSymbol<? extends FirClass> symbol = ((FirClass) type).getSymbol();
        return symbol.getClassId().asFqNameString();
    }

    @Override
    public String genericSignature(Object type) {
        FirTypeParameter typeParameter = (FirTypeParameter) type;
        String name = typeParameter.getName().asString();

        if (typeVariableNameStack == null) {
            typeVariableNameStack = new HashSet<>();
        }

        if (!typeVariableNameStack.add(name)) {
            return "Generic{" + name + "}";
        }

        StringBuilder s = new StringBuilder("Generic{").append(name);
        StringJoiner boundSigs = new StringJoiner(", ");
        for (FirTypeRef bound : typeParameter.getBounds()) {
            if (!(bound instanceof FirImplicitNullableAnyTypeRef)) {
                boundSigs.add(signature(bound));
            }
        }

        String boundSigStr = boundSigs.toString();
        if (!boundSigStr.isEmpty()) {
            s.append(" : ").append(boundSigStr);
        }

        typeVariableNameStack.remove(name);
        return s.append("}").toString();
    }

    @Override
    public String parameterizedSignature(Object type) {
        StringBuilder s = new StringBuilder(classSignature(type));
        StringJoiner joiner = new StringJoiner(", ", "<", ">");
        for (FirTypeParameterRef tp : ((FirClass) type).getTypeParameters()) {
            String signature = signature(tp);
            joiner.add(signature);
        }
        s.append(joiner);
        return s.toString();
    }

    public String resolvedTypeRef(Object type) {
        FirResolvedTypeRef resolvedTypeRef = (FirResolvedTypeRef) type;

        ConeKotlinType coneKotlinType = FirTypeUtilsKt.getConeType(resolvedTypeRef);
        ClassId classId = ConeTypeUtilsKt.getClassId(coneKotlinType);
        if (classId == null) {
            // TODO: this may not work 100% for generics with bounds ... test with bounds.
            return coneKotlinType.toString();
        }
        return classId.asFqNameString();
    }

    @Override
    public String primitiveSignature(Object type) {
        throw new UnsupportedOperationException("TODO");
    }

    public String propertySignature(FirPropertySymbol symbol) {
        String owner;
        ConeSimpleKotlinType kotlinType = symbol.getDispatchReceiverType();

        if (kotlinType instanceof ConeClassLikeType) {
            FirRegularClass regularClass = convertToRegularClass(kotlinType);
            if (regularClass == null) {
                owner = "{undefined}";
            } else {
                owner = signature(regularClass);
            }
            if (owner.contains("<")) {
                owner = owner.substring(0, owner.indexOf('<'));
            }
        } else {
            throw new UnsupportedOperationException("TODO");
        }

        return owner + "{name=" + symbol.getName().asString() + ",type=" + signature(symbol.getResolvedReturnTypeRef()) + '}';
    }

    public String methodSignature(FirFunctionSymbol<? extends FirFunction> symbol) {
        String s = classSignature(convertToRegularClass(symbol.getDispatchReceiverType()));

        if (symbol instanceof FirConstructorSymbol) {
            s += "{name=<constructor>,return=" + s;
        } else {
            s += "{name=" + symbol.getName().asString() +
                    ",return=" + signature(symbol.getResolvedReturnTypeRef());
        }

        return s + ",parameters=" + methodArgumentSignature(symbol) + '}';
    }

    private String methodArgumentSignature(FirFunctionSymbol<? extends FirFunction> sym) {
        // TODO:
//        if (sym.isStaticOrInstanceInit()) {
//            return "[]";
//        }

        StringJoiner genericArgumentTypes = new StringJoiner(",", "[", "]");
//        if (sym.getFir() == null) {
//            genericArgumentTypes.add("{undefined}");
//        } else {
        for (FirValueParameterSymbol parameterSymbol : sym.getValueParameterSymbols()) {
                genericArgumentTypes.add(signature(parameterSymbol.getFir()));
        }
//        }
        return genericArgumentTypes.toString();
    }

    @Nullable
    public FirRegularClass convertToRegularClass(@Nullable ConeKotlinType kotlinType) {
        if (kotlinType != null) {
            FirRegularClassSymbol symbol = TypeUtilsKt.toRegularClassSymbol(kotlinType, firSession);
            if (symbol != null) {
                return symbol.getFir();
            }
        }

        return null;
    }

    @Nullable
    public FirRegularClass convertToRegularClass(@Nullable FirTypeRef firTypeRef) {
        if (firTypeRef != null) {
            ConeKotlinType coneKotlinType = FirTypeUtilsKt.getConeType(firTypeRef);
            return convertToRegularClass(coneKotlinType);
        }

        return null;
    }
}