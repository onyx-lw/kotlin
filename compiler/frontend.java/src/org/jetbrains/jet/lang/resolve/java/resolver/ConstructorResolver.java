/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve.java.resolver;

import com.google.common.collect.Lists;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.DescriptorResolver;
import org.jetbrains.jet.lang.resolve.java.DescriptorResolverUtils;
import org.jetbrains.jet.lang.resolve.java.JavaDescriptorResolver;
import org.jetbrains.jet.lang.resolve.java.TypeVariableResolver;
import org.jetbrains.jet.lang.resolve.java.TypeVariableResolvers;
import org.jetbrains.jet.lang.resolve.java.data.ResolverClassData;
import org.jetbrains.jet.lang.resolve.java.descriptor.ClassDescriptorFromJvmBytecode;
import org.jetbrains.jet.lang.resolve.java.kotlinSignature.AlternativeMethodSignatureData;
import org.jetbrains.jet.lang.resolve.java.wrapper.PsiMethodWrapper;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.types.JetType;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class ConstructorResolver {
    private final JavaDescriptorResolver javaDescriptorResolver;

    public ConstructorResolver(JavaDescriptorResolver javaDescriptorResolver) {
        this.javaDescriptorResolver = javaDescriptorResolver;
    }

    @NotNull
    public Collection<ConstructorDescriptor> resolveConstructors(@NotNull ResolverClassData classData) {
        Collection<ConstructorDescriptor> constructors = Lists.newArrayList();

        PsiClass psiClass = classData.getPsiClass();

        ClassDescriptorFromJvmBytecode containingClass = classData.getClassDescriptor();
        assert psiClass != null;
        TypeVariableResolver resolverForTypeParameters = TypeVariableResolvers.classTypeVariableResolver(
                containingClass, "class " + psiClass.getQualifiedName());

        List<TypeParameterDescriptor> typeParameters = containingClass.getTypeConstructor().getParameters();

        PsiMethod[] psiConstructors = psiClass.getConstructors();

        boolean isStatic = psiClass.hasModifierProperty(PsiModifier.STATIC);
        if (containingClass.getKind() == ClassKind.OBJECT || containingClass.getKind() == ClassKind.CLASS_OBJECT) {
            constructors.add(DescriptorResolver.createPrimaryConstructorForObject(containingClass));
        }
        else if (psiConstructors.length == 0) {
            if (javaDescriptorResolver.getTrace().get(BindingContext.CONSTRUCTOR, psiClass) != null) {
                constructors.add(javaDescriptorResolver.getTrace().get(BindingContext.CONSTRUCTOR, psiClass));
            }
            else {
                // We need to create default constructors for classes and abstract classes.
                // Example:
                // class Kotlin() : Java() {}
                // abstract public class Java {}
                if (!psiClass.isInterface()) {
                    ConstructorDescriptorImpl constructorDescriptor = new ConstructorDescriptorImpl(
                            containingClass,
                            Collections.<AnnotationDescriptor>emptyList(),
                            false);
                    constructorDescriptor.initialize(typeParameters, Collections.<ValueParameterDescriptor>emptyList(), containingClass
                            .getVisibility(), isStatic);
                    constructors.add(constructorDescriptor);
                    javaDescriptorResolver.getTrace().record(BindingContext.CONSTRUCTOR, psiClass, constructorDescriptor);
                }
                if (psiClass.isAnnotationType()) {
                    // A constructor for an annotation type takes all the "methods" in the @interface as parameters
                    ConstructorDescriptorImpl constructorDescriptor = new ConstructorDescriptorImpl(
                            containingClass,
                            Collections.<AnnotationDescriptor>emptyList(),
                            false);

                    List<ValueParameterDescriptor> valueParameters = Lists.newArrayList();
                    PsiMethod[] methods = psiClass.getMethods();
                    for (int i = 0; i < methods.length; i++) {
                        PsiMethod method = methods[i];
                        if (method instanceof PsiAnnotationMethod) {
                            PsiAnnotationMethod annotationMethod = (PsiAnnotationMethod) method;
                            assert annotationMethod.getParameterList().getParameters().length == 0;

                            PsiType returnType = annotationMethod.getReturnType();

                            // We take the following heuristical convention:
                            // if the last method of the @interface is an array, we convert it into a vararg
                            JetType varargElementType = null;
                            if (i == methods.length - 1 && (returnType instanceof PsiArrayType)) {
                                varargElementType = javaDescriptorResolver.getSemanticServices().getTypeTransformer()
                                        .transformToType(((PsiArrayType) returnType).getComponentType(), resolverForTypeParameters);
                            }

                            assert returnType != null;
                            valueParameters.add(new ValueParameterDescriptorImpl(
                                    constructorDescriptor,
                                    i,
                                    Collections.<AnnotationDescriptor>emptyList(),
                                    Name.identifier(method.getName()),
                                    false,
                                    javaDescriptorResolver.getSemanticServices().getTypeTransformer()
                                            .transformToType(returnType, resolverForTypeParameters),
                                    annotationMethod.getDefaultValue() != null,
                                    varargElementType));
                        }
                    }

                    constructorDescriptor.initialize(typeParameters, valueParameters, containingClass.getVisibility(), isStatic);
                    constructors.add(constructorDescriptor);
                    javaDescriptorResolver.getTrace().record(BindingContext.CONSTRUCTOR, psiClass, constructorDescriptor);
                }
            }
        }
        else {
            for (PsiMethod psiConstructor : psiConstructors) {
                ConstructorDescriptor constructor = resolveConstructor(psiClass, classData, isStatic, psiConstructor);
                if (constructor != null) {
                    constructors.add(constructor);
                }
            }
        }

        for (ConstructorDescriptor constructor : constructors) {
            ((ConstructorDescriptorImpl) constructor).setReturnType(containingClass.getDefaultType());
        }

        return constructors;
    }

    @Nullable
    private ConstructorDescriptor resolveConstructor(
            PsiClass psiClass,
            ResolverClassData classData,
            boolean aStatic,
            PsiMethod psiConstructor
    ) {
        PsiMethodWrapper constructor = new PsiMethodWrapper(psiConstructor);

        //noinspection deprecation
        if (constructor.getJetConstructor().hidden()) {
            return null;
        }

        if (javaDescriptorResolver.getTrace().get(BindingContext.CONSTRUCTOR, psiConstructor) != null) {
            return javaDescriptorResolver.getTrace().get(BindingContext.CONSTRUCTOR, psiConstructor);
        }

        ConstructorDescriptorImpl constructorDescriptor = new ConstructorDescriptorImpl(
                classData.getClassDescriptor(),
                Collections.<AnnotationDescriptor>emptyList(), // TODO
                false);

        String context = "constructor of class " + psiClass.getQualifiedName();
        JavaDescriptorResolver.ValueParameterDescriptors valueParameterDescriptors = javaDescriptorResolver.resolveParameterDescriptors(
                constructorDescriptor, constructor.getParameters(),
                TypeVariableResolvers.classTypeVariableResolver(classData.getClassDescriptor(), context));

        if (valueParameterDescriptors.getReceiverType() != null) {
            throw new IllegalStateException();
        }

        AlternativeMethodSignatureData alternativeMethodSignatureData =
                new AlternativeMethodSignatureData(constructor, valueParameterDescriptors, null,
                                                   Collections.<TypeParameterDescriptor>emptyList());
        if (alternativeMethodSignatureData.isAnnotated() && !alternativeMethodSignatureData.hasErrors()) {
            valueParameterDescriptors = alternativeMethodSignatureData.getValueParameters();
        }
        else if (alternativeMethodSignatureData.hasErrors()) {
            javaDescriptorResolver.getTrace().record(BindingContext.ALTERNATIVE_SIGNATURE_DATA_ERROR, constructorDescriptor,
                                                     alternativeMethodSignatureData.getError());
        }

        constructorDescriptor.initialize(classData.getClassDescriptor().getTypeConstructor().getParameters(),
                                         valueParameterDescriptors.getDescriptors(),
                                         DescriptorResolverUtils.resolveVisibility(psiConstructor, constructor.getJetConstructor()),
                                         aStatic);
        javaDescriptorResolver.getTrace().record(BindingContext.CONSTRUCTOR, psiConstructor, constructorDescriptor);
        return constructorDescriptor;
    }
}