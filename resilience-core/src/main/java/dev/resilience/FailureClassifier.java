package dev.resilience;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@FunctionalInterface
public interface FailureClassifier {
    FailureClassifier DEFAULT =
            e ->
                    e == null
                            ? Outcome.SUCCESS
                            : (e instanceof IOException || e instanceof TimeoutException
                            ? Outcome.FAILURE
                            : Outcome.IGNORE);

    Outcome classify(Throwable failure);

    enum Outcome {
        SUCCESS,
        FAILURE,
        IGNORE
    }
}
