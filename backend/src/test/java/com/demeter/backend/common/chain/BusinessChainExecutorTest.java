package com.demeter.backend.common.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessChainExecutorTest {

    private final BusinessChainExecutor executor = new BusinessChainExecutor(new SimpleMeterRegistry());

    @Test
    void executesEveryHandlerInDeclaredOrder() {
        TestContext context = new TestContext();
        BusinessChain<TestContext, List<String>> chain = BusinessChain.of(
                "test.ordered",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("first", item -> item.steps.add("first")),
                        BusinessHandler.named("second", item -> item.steps.add("second")),
                        BusinessHandler.named("third", item -> item.steps.add("third"))),
                item -> List.copyOf(item.steps));

        assertThat(executor.execute(chain, context)).containsExactly("first", "second", "third");
    }

    @Test
    void stopsAfterAnExplicitHalt() {
        TestContext context = new TestContext();
        BusinessChain<TestContext, List<String>> chain = BusinessChain.of(
                "test.halt",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("first", item -> item.steps.add("first")),
                        BusinessHandler.named("halt", item -> {
                            item.steps.add("halt");
                            item.halt();
                        }),
                        BusinessHandler.named("never", item -> item.steps.add("never"))),
                item -> List.copyOf(item.steps));

        assertThat(executor.execute(chain, context)).containsExactly("first", "halt");
    }

    @Test
    void stopsImmediatelyWhenAHandlerFails() {
        TestContext context = new TestContext();
        BusinessChain<TestContext, List<String>> chain = BusinessChain.of(
                "test.failure",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("first", item -> item.steps.add("first")),
                        BusinessHandler.named("failure", item -> {
                            throw new IllegalStateException("expected");
                        }),
                        BusinessHandler.named("never", item -> item.steps.add("never"))),
                item -> List.copyOf(item.steps));

        assertThatThrownBy(() -> executor.execute(chain, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected");
        assertThat(context.steps).containsExactly("first");
    }

    @Test
    void rejectsAChainThatCompletesWithoutAResult() {
        BusinessChain<TestContext, String> chain = BusinessChain.of(
                "test.missing-result",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(BusinessHandler.named("complete", context -> context.steps.add("complete"))),
                context -> null);

        assertThatThrownBy(() -> executor.execute(chain, new TestContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Business chain returned no result: test.missing-result");
    }

    private static final class TestContext extends BusinessContext {
        private final List<String> steps = new ArrayList<>();
    }
}
