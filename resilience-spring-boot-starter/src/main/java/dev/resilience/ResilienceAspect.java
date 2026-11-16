package dev.resilience;

import java.util.concurrent.CompletionStage;

import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;

@Aspect
public class ResilienceAspect {
    private final ResilienceExecutor executor;

    public ResilienceAspect(ResilienceExecutor executor) {
        this.executor = executor;
    }

    @Around("@annotation(annotation)")
    public Object invoke(ProceedingJoinPoint call, Resilient annotation) throws Throwable {
        var method = ((org.aspectj.lang.reflect.MethodSignature) call.getSignature()).getMethod();
        if (CompletionStage.class.isAssignableFrom(method.getReturnType()))
            throw new IllegalArgumentException("Use programmatic executeStage for asynchronous methods");
        return executor.execute(
                annotation.value(),
                annotation.safety(),
                () -> {
                    try {
                        return call.proceed();
                    } catch (Exception e) {
                        throw e;
                    } catch (Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new IllegalStateException(e);
                    }
                });
    }
}
