package com.github.davidmoten.fsm.model;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.davidmoten.fsm.graph.Graph;
import com.github.davidmoten.fsm.graph.GraphEdge;
import com.github.davidmoten.fsm.graph.GraphNode;
import com.github.davidmoten.fsm.graph.GraphmlWriter;
import com.github.davidmoten.fsm.runtime.Event;
import com.github.davidmoten.fsm.runtime.EventVoid;
import com.github.davidmoten.guavamini.Preconditions;

public final class StateMachine<T> {

    private final Class<T> cls;
    private final List<Transition<?, ?>> transitions = new ArrayList<>();
    private final Set<State<?>> states = new HashSet<State<?>>();
    private final State<Void> initialState;

    private StateMachine(Class<T> cls) {
        this.cls = cls;
        this.initialState = new State<Void>(this, "Initial", EventVoid.class);
    }

    public static <T> StateMachine<T> create(Class<T> cls) {
        return new StateMachine<T>(cls);
    }

    public Class<T> cls() {
        return cls;
    }

    public <R> State<R> state(String name, Class<? extends Event<R>> eventClass) {
        Preconditions.checkNotNull(name);
        if (name.equals("Initial")) {
            name = name.concat("_1");
        }
        State<R> state = new State<R>(this, name, eventClass);
        states.add(state);
        return state;
    }

    public <R, S> StateMachine<T> addTransition(State<R> state, State<S> other) {
        Transition<R, S> transition = new Transition<R, S>(state, other);
        System.out.println("adding " + transition);
        for (Transition<?, ?> t : transitions) {
            if (t.from() == state && t.to() == other) {
                throw new IllegalArgumentException(
                        "the transition already exists: " + state.name() + " -> " + other.name());
            }
        }
        transitions.add(transition);
        return this;
    }

    <S> StateMachine<T> addInitialTransition(State<S> other) {
        Transition<Void, S> transition = new Transition<Void, S>(initialState, other);
        System.out.println("adding " + transition);
        transitions.add(transition);
        states.add(initialState);
        states.add(other);
        return this;
    }

    public void generateClasses(File directory, String pkg) {
        new Generator<T>(this, directory, pkg).generate();
    }

    public List<Transition<?, ?>> transitions() {
        return transitions;
    }

    public String documentationHtml() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(bytes);
        out.println("<html/>");
        out.println("<body>");
        // states
        out.println("<h2>States</h2>");
        states.stream().map(state -> state.name()).sorted()
                .forEach(state -> out.println("<p class=\"state\"><b>" + state + "</b></p>"));

        // events
        out.println("<h2>Events</h2>");
        states.stream().filter(state -> !state.isInitial())
                .map(state -> state.eventClass().getSimpleName()).distinct().sorted()
                .forEach(event -> out.println("<p class=\"event\"><i>" + event + "</i></p>"));

        // transition table
        // state onEntry template

        out.println("</body>");
        out.println("</html>");
        out.close();
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    public String graphml() {
        List<GraphNode> nodes = states.stream()
                .map(state -> new GraphNode(state.name(),
                        state.name() + "\n[" + state.eventClass().getSimpleName() + "]", true))
                .collect(Collectors.toList());
        Map<String, GraphNode> map = nodes.stream()
                .collect(Collectors.toMap(node -> node.name(), node -> node));
        List<GraphEdge> edges = transitions.stream().map(t -> {
            GraphNode from = map.get(t.from().name());
            GraphNode to = map.get(t.to().name());
            return new GraphEdge(from, to);
        }).collect(Collectors.toList());
        Graph graph = new Graph(nodes, edges);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(bytes);
        new GraphmlWriter().printGraphml(out, graph);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    public boolean hasCreationTransition() {
        return transitions().stream().filter(t -> t.from().isCreationDestination()).findAny()
                .isPresent();
    }

}
