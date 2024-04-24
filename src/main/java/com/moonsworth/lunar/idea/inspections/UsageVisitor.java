package com.moonsworth.lunar.idea.inspections;

import com.intellij.codeInsight.daemon.impl.analysis.JavaHighlightUtil;
import com.intellij.psi.*;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.moonsworth.lunar.idea.utils.UsageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class UsageVisitor extends JavaElementVisitor {
    private final ResultProcessor resultProcessor;

    public UsageVisitor(ResultProcessor resultProcessor) {
        this.resultProcessor = resultProcessor;
    }

    @Override
    public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement reference) {
        JavaResolveResult result = reference.advancedResolve(true);
        PsiElement resolved = result.getElement();
        if (resolved instanceof PsiMethod method) {
            if (method.isConstructor() && "super".equals(reference.getQualifiedName())) {
                this.resultProcessor.process(
                    resolved,
                    reference.getReferenceNameElement(),
                    UsageType.SuperCtorCall.INSTANCE
                );
            }
        } else {
            this.resultProcessor.process(resolved, reference.getReferenceNameElement());
        }
    }

    @Override
    public void visitImportStaticStatement(@NotNull PsiImportStaticStatement statement) {
        PsiJavaCodeReferenceElement importReference = statement.getImportReference();
        if (importReference != null) {
            this.resultProcessor.process(importReference.resolve(), importReference.getReferenceNameElement());
        }
    }

    @Override
    public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
        this.visitReferenceElement(expression);
    }

    @Override
    public void visitNewExpression(@NotNull PsiNewExpression expression) {
        PsiType type = expression.getType();
        PsiExpressionList list = expression.getArgumentList();

        if (!(type instanceof PsiClassType)) return;

        PsiClassType.ClassResolveResult typeResult = ((PsiClassType) type).resolveGenerics();
        PsiClass aClass = typeResult.getElement();

        if (aClass == null) return;

        if (aClass instanceof PsiAnonymousClass) {
            type = ((PsiAnonymousClass) aClass).getBaseClassType();
            typeResult = ((PsiClassType) type).resolveGenerics();
            aClass = typeResult.getElement();
            if (aClass == null) {
                return;
            }
        }

        PsiResolveHelper resolveHelper = JavaPsiFacade.getInstance(expression.getProject()).getResolveHelper();
        PsiMethod[] constructors = aClass.getConstructors();

        if (constructors.length > 0 && list != null) {
            JavaResolveResult[] results = resolveHelper.multiResolveConstructor((PsiClassType) type, list, list);
            MethodCandidateInfo result = null;

            if (results.length == 1) {
                result = (MethodCandidateInfo) results[0];
            }

            PsiMethod constructor = result == null ? null : result.getElement();
            if (constructor != null && expression.getClassOrAnonymousClassReference() != null) {
                if (expression.getClassReference() == null && constructor.getParameterList().getParametersCount() == 0) {
                    return;
                }
                this.resultProcessor.process(constructor, expression.getClassOrAnonymousClassReference());
            }
        }
    }

    @Override
    public void visitMethod(@NotNull PsiMethod method) {
        if (method.isConstructor()) {
            this.checkImplicitCallToSuper(method);
        }
    }

    private void checkImplicitCallToSuper(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        assert (containingClass != null);
        PsiClass superClass = containingClass.getSuperClass();
        PsiMethod superCtor = this.getDefaultConstructor(superClass);

        if (superCtor != null) {
            if (superClass instanceof PsiAnonymousClass) {
                PsiExpressionList argumentList = ((PsiAnonymousClass) superClass).getArgumentList();
                if (argumentList != null && argumentList.getExpressions().length > 0) {
                    return;
                }
            }

            PsiCodeBlock body = method.getBody();
            if (body != null) {
                PsiStatement[] statements = body.getStatements();
                if (statements.length != 0) {
                    if (JavaHighlightUtil.isSuperOrThisCall(statements[0], true, true)) {
                        this.resultProcessor.process(
                            superCtor,
                            method.getNameIdentifier(),
                            UsageType.SuperCtorCall.INSTANCE
                        );
                    }
                }
            }
        }
    }

    @Override
    public void visitClass(@NotNull PsiClass aClass) {
        if (aClass instanceof PsiTypeParameter) return;

        PsiMethod[] currentConstructors = aClass.getConstructors();
        PsiClass superClass = aClass.getSuperClass();
        PsiMethod superCtor = this.getDefaultConstructor(superClass);

        if (currentConstructors.length == 0 && superCtor != null) {
            boolean isAnonymous = aClass instanceof PsiAnonymousClass;
            PsiExpressionList argumentList = ((PsiAnonymousClass) aClass).getArgumentList();

            if (isAnonymous && argumentList != null && argumentList.getExpressions().length > 0) {
                return;
            }

            this.resultProcessor.process(
                superCtor,
                isAnonymous ? ((PsiAnonymousClass) aClass).getBaseClassReference() : aClass.getNameIdentifier(),
                UsageType.SuperCtorCall.INSTANCE
            );
        }
    }

    private PsiMethod getDefaultConstructor(@Nullable PsiClass owner) {
        if (owner == null) return null;

        return Arrays.stream(owner.getConstructors())
            .filter(it -> it.getParameterList().getParametersCount() == 0)
            .findFirst()
            .orElse(null);
    }

    @FunctionalInterface
    public interface ResultProcessor {
        void process(PsiElement refElement, PsiElement elementToHighlight, UsageType usage);

        default void process(PsiElement refElement, PsiElement elementToHighlight) {
            this.process(refElement, elementToHighlight, UsageType.Generic.INSTANCE);
        }
    }
}
