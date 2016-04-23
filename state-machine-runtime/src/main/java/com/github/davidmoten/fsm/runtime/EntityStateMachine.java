package com.github.davidmoten.fsm.runtime;

import java.util.List;

public interface EntityStateMachine<T> extends ObjectState<T> {

    boolean transitionOccurred();

    EntityStateMachine<T> event(Event<?> event);

    List<Signal<T, ?>> signalsToSelf();

    List<Signal<?, ?>> signalsToOther();
}
