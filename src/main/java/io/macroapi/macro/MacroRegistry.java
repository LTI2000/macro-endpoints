package io.macroapi.macro;

import io.macroapi.interpret.CallGraph;
import io.macroapi.interpret.Cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A catalogue of the macros a service publishes, each paired with a representative request.
 *
 * <p>The exemplar is what makes the registry more than a list. Documenting or graphing a macro means
 * expanding it, and expansion needs an argument; storing one alongside each macro lets the whole
 * catalogue be rendered in a single pass with no further input. That is how the build produces an
 * always-current site: every registered macro is folded through the documentation, cost and graph
 * interpreters, and the output is published.</p>
 *
 * <p>Exemplars are expanded but never executed, so they may safely name resources that do not
 * exist.</p>
 *
 * <p>Instances are mutable and not thread-safe; populate one during start-up and treat it as
 * read-only thereafter.</p>
 */
public final class MacroRegistry {

    /**
     * One catalogue entry: a macro together with the request used to render it.
     *
     * @param macro    the registered macro
     * @param exemplar a representative request, used for documentation only
     * @param <Q>      the request type
     * @param <R>      the response type
     */
    public record Entry<Q, R>(Macro<Q, R> macro, Q exemplar) {

        /**
         * Canonical constructor.
         *
         * @param macro    the macro, non-null
         * @param exemplar the sample request
         */
        public Entry {
            Objects.requireNonNull(macro, "macro");
        }

        /**
         * The macro's name.
         *
         * @return the identifier
         */
        public String name() {
            return macro.spec().name();
        }

        /**
         * Renders this entry's dependency graph as Graphviz DOT.
         *
         * @return the DOT source
         */
        public String dot() {
            return graph().toDot(name());
        }

        /**
         * Renders this entry's dependency graph as PlantUML.
         *
         * @return the PlantUML source
         */
        public String plantUml() {
            return graph().toPlantUml(name());
        }

        /**
         * Derives this entry's dependency graph.
         *
         * @return the graph
         */
        public CallGraph graph() {
            return macro.graph(exemplar);
        }

        /**
         * Estimates this entry's execution cost.
         *
         * @return the estimate
         */
        public Cost cost() {
            return macro.estimateCost(exemplar);
        }

        /**
         * Renders this entry as a Markdown section: contract, cost, and structural outline.
         *
         * @return the Markdown fragment
         */
        public String markdown() {
            MacroSpec spec = macro.spec();
            StringBuilder out = new StringBuilder();
            out.append("### ").append(spec.name()).append("\n\n");
            out.append(spec.summary()).append("\n\n");
            out.append("| property | value |\n|---|---|\n");
            out.append("| stability | ").append(spec.stability()).append(" |\n");
            out.append("| tags | ").append(spec.tags().isEmpty() ? "-" : String.join(", ", spec.tags())).append(" |\n");
            out.append("| estimated cost | ").append(cost().format()).append(" |\n\n");
            out.append("Structure for the sample request `").append(exemplar).append("`:\n\n");
            out.append(macro.describe(exemplar).renderMarkdown()).append("\n\n");
            return out.toString();
        }
    }

    /** Creates an empty registry. */
    public MacroRegistry() {
    }

    private final Map<String, Entry<?, ?>> entries = new LinkedHashMap<>();

    /**
     * Registers a macro with the request used to render its documentation.
     *
     * @param macro    the macro to publish
     * @param exemplar a representative request, expanded but never executed
     * @param <Q>      the request type
     * @param <R>      the response type
     * @return this registry, for chaining
     * @throws IllegalStateException if a macro of the same name is already registered
     */
    public <Q, R> MacroRegistry register(Macro<Q, R> macro, Q exemplar) {
        Objects.requireNonNull(macro, "macro");
        String name = macro.spec().name();
        if (entries.containsKey(name)) {
            throw new IllegalStateException("duplicate macro name: " + name);
        }
        entries.put(name, new Entry<>(macro, exemplar));
        return this;
    }

    /**
     * Looks up an entry by macro name.
     *
     * @param name the macro identifier
     * @return the entry, if registered
     */
    public Optional<Entry<?, ?>> find(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /**
     * All entries, in registration order.
     *
     * @return an immutable snapshot
     */
    public List<Entry<?, ?>> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Renders the whole catalogue as one Markdown document.
     *
     * @param title the document heading
     * @return the Markdown source
     */
    public String markdown(String title) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        out.append("Generated from the registered macros by folding each expansion through the ")
                .append("documentation and cost interpreters. No API call is made to produce this page.\n\n");
        out.append("## Catalogue\n\n| macro | stability | estimated cost |\n|---|---|---|\n");
        for (Entry<?, ?> entry : entries.values()) {
            out.append("| [").append(entry.name()).append("](#")
                    .append(entry.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-')).append(") | ")
                    .append(entry.macro().spec().stability()).append(" | ")
                    .append(entry.cost().format()).append(" |\n");
        }
        out.append("\n## Details\n\n");
        for (Entry<?, ?> entry : entries.values()) {
            out.append(entry.markdown());
        }
        return out.toString();
    }

    /**
     * Renders every registered macro's dependency graph as Graphviz DOT.
     *
     * @return a map from macro name to DOT source, in registration order
     */
    public Map<String, String> dotDiagrams() {
        Map<String, String> diagrams = new LinkedHashMap<>();
        entries.values().forEach(entry -> diagrams.put(entry.name(), entry.dot()));
        return diagrams;
    }

    /**
     * Renders every registered macro's dependency graph as PlantUML.
     *
     * @return a map from macro name to PlantUML source, in registration order
     */
    public Map<String, String> plantUmlDiagrams() {
        Map<String, String> diagrams = new LinkedHashMap<>();
        entries.values().forEach(entry -> diagrams.put(entry.name(), entry.plantUml()));
        return diagrams;
    }

    /**
     * Every distinct low-level endpoint reachable from any registered macro, sorted by name.
     *
     * <p>Derived from the graphs rather than declared, so it cannot fall out of date. Useful as a
     * deployment check: the set of upstreams a service actually depends on.</p>
     *
     * @return the endpoint names
     */
    public List<String> reachableEndpoints() {
        List<String> names = new ArrayList<>();
        for (Entry<?, ?> entry : entries.values()) {
            entry.graph().nodes().stream()
                    .filter(node -> node.kind() == CallGraph.NodeKind.ENDPOINT)
                    .map(CallGraph.GraphNode::label)
                    .filter(name -> !names.contains(name))
                    .forEach(names::add);
        }
        return names.stream().sorted().toList();
    }
}
