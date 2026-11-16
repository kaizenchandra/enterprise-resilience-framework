package dev.resilience;

import io.opentelemetry.context.Context;
import org.slf4j.MDC;

/**
 * Copies only supported context, and restores prior worker values even after exceptions.
 */
public final class ObservabilityTaskContext implements TaskContext {
    public Snapshot capture() {
        var framework = TaskContext.FRAMEWORK.capture();
        var otel = Context.current();
        var mdc = MDC.getCopyOfContextMap();
        return () -> {
            var previous = MDC.getCopyOfContextMap();
            var fs = framework.open();
            var os = otel.makeCurrent();
            if (mdc == null) MDC.clear();
            else MDC.setContextMap(mdc);
            return () -> {
                try {
                    if (previous == null) MDC.clear();
                    else MDC.setContextMap(previous);
                } finally {
                    os.close();
                    fs.close();
                }
            };
        };
    }
}
