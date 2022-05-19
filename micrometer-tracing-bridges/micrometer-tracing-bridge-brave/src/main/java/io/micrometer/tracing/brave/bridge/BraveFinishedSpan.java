/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micrometer.tracing.brave.bridge;

import java.util.Collection;
import java.util.Map;

import brave.handler.MutableSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.exporter.FinishedSpan;

/**
 * Brave implementation of a {@link FinishedSpan}.
 *
 * @author Marcin Grzejszczak
 * @since 1.0.0
 */
public class BraveFinishedSpan implements FinishedSpan {

    private final MutableSpan mutableSpan;

    public BraveFinishedSpan(MutableSpan mutableSpan) {
        this.mutableSpan = mutableSpan;
    }

    public static FinishedSpan fromBrave(MutableSpan mutableSpan) {
        return new BraveFinishedSpan(mutableSpan);
    }

    public static MutableSpan toBrave(FinishedSpan mutableSpan) {
        return ((BraveFinishedSpan) mutableSpan).mutableSpan;
    }

    @Override
    public FinishedSpan setName(String name) {
        this.mutableSpan.name(name);
        return this;
    }

    @Override
    public String getName() {
        return this.mutableSpan.name();
    }

    @Override
    public long getStartTimestamp() {
        return this.mutableSpan.startTimestamp();
    }

    @Override
    public long getEndTimestamp() {
        return this.mutableSpan.finishTimestamp();
    }

    @Override
    public FinishedSpan setTags(Map<String, String> tags) {
        this.mutableSpan.tags().clear();
        tags.forEach(this.mutableSpan::tag);
        return this;
    }

    @Override
    public Map<String, String> getTags() {
        return this.mutableSpan.tags();
    }

    @Override
    public FinishedSpan setEvents(Collection<Map.Entry<Long, String>> events) {
        this.mutableSpan.annotations().clear();
        events.forEach(e -> this.mutableSpan.annotate(e.getKey(), e.getValue()));
        return this;
    }

    @Override
    public Collection<Map.Entry<Long, String>> getEvents() {
        return this.mutableSpan.annotations();
    }

    @Override
    public String getSpanId() {
        return this.mutableSpan.id();
    }

    @Override
    public String getParentId() {
        return this.mutableSpan.parentId();
    }

    @Override
    public String getRemoteIp() {
        return this.mutableSpan.remoteIp();
    }

    @Override
    public String getLocalIp() {
        return this.mutableSpan.localIp();
    }

    @Override
    public FinishedSpan setLocalIp(String ip) {
        this.mutableSpan.localIp(ip);
        return this;
    }

    @Override
    public int getRemotePort() {
        return this.mutableSpan.remotePort();
    }

    @Override
    public FinishedSpan setRemotePort(int port) {
        this.mutableSpan.remotePort(port);
        return this;
    }

    @Override
    public String getTraceId() {
        return this.mutableSpan.traceId();
    }

    @Override
    public Throwable getError() {
        return this.mutableSpan.error();
    }

    @Override
    public FinishedSpan setError(Throwable error) {
        this.mutableSpan.error(error);
        return this;
    }

    @Override
    public Span.Kind getKind() {
        if (this.mutableSpan.kind() == null) {
            return null;
        }
        return Span.Kind.valueOf(this.mutableSpan.kind().name());
    }

    @Override
    public String getRemoteServiceName() {
        return this.mutableSpan.remoteServiceName();
    }

    @Override
    public FinishedSpan setRemoteServiceName(String remoteServiceName) {
        this.mutableSpan.remoteServiceName(remoteServiceName);
        return this;
    }

    @Override
    public String toString() {
        return "BraveFinishedSpan{" + "mutableSpan=" + mutableSpan + '}';
    }

}
