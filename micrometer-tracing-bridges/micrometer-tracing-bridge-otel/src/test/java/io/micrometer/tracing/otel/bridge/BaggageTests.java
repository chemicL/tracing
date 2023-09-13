/**
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.tracing.otel.bridge;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.common.util.internal.logging.InternalLogger;
import io.micrometer.common.util.internal.logging.InternalLoggerFactory;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import io.micrometer.tracing.otel.propagation.BaggageTextMapPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.BDDAssertions.then;

class BaggageTests {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(BaggageTests.class);

    public static final String KEY_1 = "key1";

    public static final String VALUE_1 = "value1";

    public static final String KEY_2 = "key2";

    public static final String VALUE_2 = "value2";

    SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn())
        .build();

    OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(sdkTracerProvider)
        .setPropagators(ContextPropagators.create(B3Propagator.injectingSingleHeader()))
        .build();

    io.opentelemetry.api.trace.Tracer otelTracer = openTelemetrySdk.getTracer("io.micrometer.micrometer-tracing");

    OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();

    OtelBaggageManager otelBaggageManager = new OtelBaggageManager(otelCurrentTraceContext,
            Collections.singletonList(KEY_1), Collections.emptyList());

    ContextPropagators contextPropagators = ContextPropagators
        .create(TextMapPropagator.composite(W3CBaggagePropagator.getInstance(), W3CTraceContextPropagator.getInstance(),
                new BaggageTextMapPropagator(Collections.singletonList(KEY_1), otelBaggageManager)));

    OtelPropagator propagator = new OtelPropagator(contextPropagators, otelTracer);

    Tracer tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {
    }, otelBaggageManager);

    ObservationRegistry observationRegistry = ObservationThreadLocalAccessor.getInstance().getObservationRegistry();

    @Test
    void canSetAndGetBaggage() {
        // GIVEN
        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
            // WHEN
            try (BaggageInScope bs1 = this.tracer.createBaggageInScope(KEY_1, VALUE_1);
                    BaggageInScope bs2 = this.tracer.createBaggageInScope(KEY_2, VALUE_2)) {
                // THEN
                then(tracer.getBaggage(KEY_1).get()).isEqualTo(VALUE_1);
                then(tracer.getBaggage(KEY_2).get()).isEqualTo(VALUE_2);
            }
        }
    }

    @Test
    void canSetAndGetBaggageWithLegacyApi() {
        // GIVEN
        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
            // WHEN
            try (BaggageInScope bs = this.tracer.createBaggage(KEY_1, VALUE_1).makeCurrent()) {
                // THEN
                then(tracer.getBaggage(KEY_1).get()).isEqualTo(VALUE_1);
            }
        }
    }

    @Test
    void injectAndExtractKeepsTheBaggage() {
        // GIVEN
        Map<String, String> carrier = new HashMap<>();

        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
            try (BaggageInScope bs = this.tracer.createBaggageInScope(KEY_1, VALUE_1)) {
                // WHEN
                this.propagator.inject(tracer.currentTraceContext().context(), carrier, Map::put);

                // THEN
                then(carrier.get(KEY_1)).isEqualTo(VALUE_1);
            }
        }

        // WHEN
        Span extractedSpan = propagator.extract(carrier, Map::get).start();

        // THEN
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(extractedSpan)) {
            then(tracer.getBaggage(KEY_1).get(extractedSpan.context())).isEqualTo(VALUE_1);
            try (BaggageInScope baggageInScope = tracer.getBaggage(KEY_1).makeCurrent()) {
                then(baggageInScope.get()).isEqualTo(VALUE_1);
            }
        }
    }

    @Test
    void baggageWithContextPropagation() throws InterruptedException, ExecutionException, TimeoutException {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(tracer));
        Hooks.enableAutomaticContextPropagation();
        ExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        Scheduler scheduler = Schedulers.fromExecutor(executorService);

        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
            try (BaggageInScope scope = this.tracer.createBaggageInScope(KEY_1, VALUE_1)) {
                String baggageOutside = this.tracer.getBaggage(KEY_1).get();
                then(baggageOutside).isEqualTo(VALUE_1);
                log.info(
                        "BAGGAGE OUTSIDE OF REACTOR [" + baggageOutside + "], thread [" + Thread.currentThread() + "]");
                String baggageFromReactor = Mono.just(KEY_1)
                    .delayElement(Duration.ofMillis(1), scheduler)
                    .tap(Micrometer.observation(observationRegistry))
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(s -> Mono.just(this.tracer.getBaggage(s).get())
                        .doOnNext(baggage -> log.info("BAGGAGE IN OF REACTOR [" + baggageOutside + "], thread ["
                                + Thread.currentThread() + "]")))
                    .block();
                then(baggageFromReactor).isEqualTo(VALUE_1);
                then(tracer.currentSpan()).isEqualTo(span);
            }
        }
        then(tracer.currentSpan()).isNull();

        Future<Boolean> submit = executorService.submit(() -> tracer.currentSpan() == null);
        boolean noCurrentSpan = submit.get(1, TimeUnit.SECONDS);

        Assertions.assertThat(noCurrentSpan).isTrue();
    }

    @Test
    void baggageWithContextPropagationWithLegacyApi() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(tracer));
        Hooks.enableAutomaticContextPropagation();

        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
            try (BaggageInScope scope = this.tracer.createBaggage(KEY_1, VALUE_1).makeCurrent()) {
                String baggageOutside = this.tracer.getBaggage(KEY_1).get();
                then(baggageOutside).isEqualTo(VALUE_1);
                log.info(
                        "BAGGAGE OUTSIDE OF REACTOR [" + baggageOutside + "], thread [" + Thread.currentThread() + "]");
                String baggageFromReactor = Mono.just(KEY_1)
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(s -> Mono.just(this.tracer.getBaggage(s).get())
                        .doOnNext(baggage -> log.info("BAGGAGE IN OF REACTOR [" + baggageOutside + "], thread ["
                                + Thread.currentThread() + "]")))
                    .block();
                then(baggageFromReactor).isEqualTo(VALUE_1);
            }
        }
    }

    @AfterAll
    static void clear() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(ObservationAwareSpanThreadLocalAccessor.KEY);
    }

}
