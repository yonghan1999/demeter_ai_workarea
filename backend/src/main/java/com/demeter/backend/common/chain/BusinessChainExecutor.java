package com.demeter.backend.common.chain;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BusinessChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(BusinessChainExecutor.class);

    private final MeterRegistry meterRegistry;

    public BusinessChainExecutor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <C extends BusinessContext, R> R execute(BusinessChain<C, R> chain, C context) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(context, "context");
        Timer.Sample chainSample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            for (BusinessHandler<C> handler : chain.handlers()) {
                if (context.isHalted()) {
                    outcome = "halted";
                    break;
                }
                executeHandler(chain.name(), handler, context);
            }
            if (context.isHalted()) {
                outcome = "halted";
            }
            R result = chain.resultFactory().apply(context);
            if (result == null) {
                throw new IllegalStateException("Business chain returned no result: " + chain.name());
            }
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            chainSample.stop(Timer.builder("demeter.business.chain.duration")
                    .description("Business chain execution duration")
                    .tag("chain", chain.name())
                    .tag("mode", chain.executionMode().name().toLowerCase(java.util.Locale.ROOT))
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private <C extends BusinessContext> void executeHandler(
            String chainName,
            BusinessHandler<C> handler,
            C context) {
        Timer.Sample handlerSample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            handler.handle(context);
        } catch (RuntimeException exception) {
            outcome = "failure";
            log.debug("Business handler failed: chain={}, handler={}", chainName, handler.name(), exception);
            throw exception;
        } finally {
            handlerSample.stop(Timer.builder("demeter.business.handler.duration")
                    .description("Business handler execution duration")
                    .tag("chain", chainName)
                    .tag("handler", handler.name())
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
