package io.macroapi.interpret;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A dataflow graph of the calls a plan will make, derived by {@link GraphAlgebra}.
 *
 * <p>The representation is a graph <em>fragment</em> rather than a finished graph, which is what
 * makes it foldable. Alongside its nodes and edges a fragment records its {@code entries} (the
 * nodes that begin it) and {@code exits} (the nodes that finish it), so two fragments can be
 * composed without knowing anything about their internals: sequencing wires every exit of the first
 * to every entry of the second, while parallel composition simply unions both and keeps both sets
 * of endpoints. The empty fragment is the identity for both, which is why {@code Pure} and
 * {@code Transform} nodes cost nothing structurally.</p>
 *
 * @param nodes    the call and reduction nodes, in discovery order
 * @param edges    the directed dependencies between them
 * @param entries  identifiers of the nodes at which this fragment starts
 * @param exits    identifiers of the nodes at which this fragment finishes
 * @param clusters named groupings drawn as boxes, one per macro boundary
 */
public record CallGraph(List<GraphNode> nodes,
                        List<GraphEdge> edges,
                        List<String> entries,
                        List<String> exits,
                        List<Cluster> clusters) {

    /** A node in the graph. */
    public enum NodeKind {
        /** A low-level endpoint call. */
        ENDPOINT,
        /** A catamorphism reducing an intermediate result. */
        REDUCTION,
        /** A recursive expansion driven by a coalgebra. */
        RECURSION,
        /** A continuation that could not be explored statically. */
        DYNAMIC,
        /** A constant failure. */
        FAILURE
    }

    /** How one node depends on another. */
    public enum EdgeKind {
        /** The target consumes the source's result. */
        SEQUENTIAL,
        /** The target's input is chosen from the source's result at runtime. */
        DYNAMIC,
        /** The target runs only if the source fails. */
        RECOVERY,
        /** The target repeats, feeding itself. */
        LOOP
    }

    /**
     * A vertex.
     *
     * @param id    a graph-unique identifier
     * @param label the text drawn in the node
     * @param kind  the node category, which selects its shape and colour
     * @param note  supplementary text such as a request signature, possibly empty
     */
    public record GraphNode(String id, String label, NodeKind kind, String note) {
        /**
         * Canonical constructor.
         *
         * @param id    unique identifier, non-null
         * @param label display text, non-null
         * @param kind  category, non-null
         * @param note  supplementary text, non-null
         */
        public GraphNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(note, "note");
        }
    }

    /**
     * A directed edge.
     *
     * @param from the source node identifier
     * @param to   the target node identifier
     * @param kind the dependency category, which selects the line style
     */
    public record GraphEdge(String from, String to, EdgeKind kind) {
        /**
         * Canonical constructor.
         *
         * @param from source identifier, non-null
         * @param to   target identifier, non-null
         * @param kind dependency category, non-null
         */
        public GraphEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * A named grouping of nodes, drawn as a labelled box.
     *
     * @param name    the boundary name
     * @param nodeIds the identifiers it encloses
     */
    public record Cluster(String name, List<String> nodeIds) {
        /**
         * Canonical constructor; defensively copies the member list.
         *
         * @param name    boundary name, non-null
         * @param nodeIds enclosed identifiers, copied
         */
        public Cluster {
            Objects.requireNonNull(name, "name");
            nodeIds = List.copyOf(nodeIds);
        }
    }

    /**
     * Canonical constructor; defensively copies every list.
     *
     * @param nodes    graph vertices
     * @param edges    graph edges
     * @param entries  starting vertices
     * @param exits    finishing vertices
     * @param clusters named groupings
     */
    public CallGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        entries = List.copyOf(entries);
        exits = List.copyOf(exits);
        clusters = List.copyOf(clusters);
    }

    /** The identity fragment for both composition rules. */
    public static final CallGraph EMPTY =
            new CallGraph(List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * A fragment consisting of a single node.
     *
     * @param node the only vertex, which is both the entry and the exit
     * @return the singleton fragment
     */
    public static CallGraph single(GraphNode node) {
        return new CallGraph(List.of(node), List.of(), List.of(node.id()), List.of(node.id()), List.of());
    }

    /**
     * Whether this fragment contains no nodes.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Composes two fragments in sequence, wiring every exit of this fragment to every entry of the
     * next.
     *
     * <p>An empty operand is absorbed, so a pure transformation between two calls does not
     * interrupt the chain.</p>
     *
     * @param next the downstream fragment
     * @param kind the dependency category for the wiring edges
     * @return the composed fragment
     */
    public CallGraph then(CallGraph next, EdgeKind kind) {
        if (isEmpty()) {
            return next;
        }
        if (next.isEmpty()) {
            return this;
        }
        List<GraphEdge> joined = new ArrayList<>(edges);
        joined.addAll(next.edges);
        for (String exit : exits) {
            for (String entry : next.entries) {
                joined.add(new GraphEdge(exit, entry, kind));
            }
        }
        return new CallGraph(concat(nodes, next.nodes), joined, entries, next.exits,
                concat(clusters, next.clusters));
    }

    /**
     * Composes two fragments side by side; both start and both finish the result.
     *
     * @param sibling the concurrent fragment
     * @return the composed fragment
     */
    public CallGraph alongside(CallGraph sibling) {
        if (isEmpty()) {
            return sibling;
        }
        if (sibling.isEmpty()) {
            return this;
        }
        return new CallGraph(concat(nodes, sibling.nodes), concat(edges, sibling.edges),
                concat(entries, sibling.entries), concat(exits, sibling.exits),
                concat(clusters, sibling.clusters));
    }

    /**
     * Adds an explicit edge between two existing nodes, used to draw a loop back-edge.
     *
     * @param from source identifier
     * @param to   target identifier
     * @param kind dependency category
     * @return the fragment with the edge added
     */
    public CallGraph withEdge(String from, String to, EdgeKind kind) {
        return new CallGraph(nodes, concat(edges, List.of(new GraphEdge(from, to, kind))),
                entries, exits, clusters);
    }

    /**
     * Encloses every node not already enclosed in a named box.
     *
     * <p>Because the fold is bottom-up, inner boundaries claim their nodes first, so a nested macro
     * keeps its own box and the enclosing macro takes only what remains. That side-steps the fact
     * that clusters in Graphviz may not overlap.</p>
     *
     * @param name the boundary name
     * @return the fragment with one more cluster
     */
    public CallGraph inCluster(String name) {
        Set<String> alreadyGrouped = clusters.stream()
                .flatMap(cluster -> cluster.nodeIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> free = nodes.stream()
                .map(GraphNode::id)
                .filter(id -> !alreadyGrouped.contains(id))
                .toList();
        if (free.isEmpty()) {
            return this;
        }
        return new CallGraph(nodes, edges, entries, exits,
                concat(clusters, List.of(new Cluster(name, free))));
    }

    /**
     * Renders the graph in Graphviz DOT.
     *
     * @param title the graph label
     * @return the DOT source
     */
    public String toDot(String title) {
        StringBuilder out = new StringBuilder();
        out.append("digraph \"").append(escape(title)).append("\" {\n");
        out.append("  rankdir=LR;\n");
        out.append("  labelloc=\"t\";\n");
        out.append("  label=\"").append(escape(title)).append("\";\n");
        out.append("  node [fontname=\"Helvetica\", fontsize=10];\n");
        out.append("  edge [fontname=\"Helvetica\", fontsize=9];\n");

        Set<String> grouped = new LinkedHashSet<>();
        int clusterIndex = 0;
        for (Cluster cluster : clusters) {
            out.append("  subgraph cluster_").append(clusterIndex++).append(" {\n");
            out.append("    label=\"").append(escape(cluster.name())).append("\";\n");
            out.append("    style=rounded; color=\"#8899aa\"; fontsize=10;\n");
            for (String id : cluster.nodeIds()) {
                grouped.add(id);
                nodeById(id).ifPresent(node -> out.append("    ").append(dotNode(node)).append('\n'));
            }
            out.append("  }\n");
        }
        for (GraphNode node : nodes) {
            if (!grouped.contains(node.id())) {
                out.append("  ").append(dotNode(node)).append('\n');
            }
        }
        for (GraphEdge edge : edges) {
            out.append("  \"").append(edge.from()).append("\" -> \"").append(edge.to()).append("\" [")
                    .append(dotEdgeStyle(edge.kind())).append("];\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * Renders the graph in PlantUML activity-diagram-free "object" notation, which the site build
     * can turn into an image without Graphviz.
     *
     * @param title the diagram title
     * @return the PlantUML source
     */
    public String toPlantUml(String title) {
        StringBuilder out = new StringBuilder();
        out.append("@startuml\n");
        out.append("!pragma layout smetana\n");
        out.append("title ").append(title).append('\n');
        out.append("left to right direction\n");
        out.append("skinparam shadowing false\n");
        out.append("skinparam rectangle {\n  BorderColor #556677\n  BackgroundColor #f4f6f8\n}\n");

        Set<String> grouped = new LinkedHashSet<>();
        for (Cluster cluster : clusters) {
            out.append("package \"").append(cluster.name()).append("\" {\n");
            for (String id : cluster.nodeIds()) {
                grouped.add(id);
                nodeById(id).ifPresent(node -> out.append("  ").append(umlNode(node)).append('\n'));
            }
            out.append("}\n");
        }
        for (GraphNode node : nodes) {
            if (!grouped.contains(node.id())) {
                out.append(umlNode(node)).append('\n');
            }
        }
        for (GraphEdge edge : edges) {
            out.append(edge.from()).append(umlArrow(edge.kind())).append(edge.to()).append('\n');
        }
        out.append("@enduml\n");
        return out.toString();
    }

    private java.util.Optional<GraphNode> nodeById(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    private static String dotNode(GraphNode node) {
        // Each part is escaped independently, then joined with DOT's own line break, so that the
        // separator survives escaping instead of being turned into a literal backslash-n.
        String text = node.note().isEmpty()
                ? escape(node.label())
                : escape(node.label()) + "\\n" + escape(node.note());
        String style = switch (node.kind()) {
            case ENDPOINT -> "shape=box, style=\"rounded,filled\", fillcolor=\"#dce9f7\"";
            case REDUCTION -> "shape=invhouse, style=filled, fillcolor=\"#e6f3e0\"";
            case RECURSION -> "shape=box3d, style=filled, fillcolor=\"#fdf3d8\"";
            case DYNAMIC -> "shape=diamond, style=dashed";
            case FAILURE -> "shape=octagon, style=filled, fillcolor=\"#f8dcdc\"";
        };
        return "\"" + node.id() + "\" [label=\"" + text + "\", " + style + "];";
    }

    private static String umlNode(GraphNode node) {
        String stereotype = switch (node.kind()) {
            case ENDPOINT -> "<<call>>";
            case REDUCTION -> "<<cata>>";
            case RECURSION -> "<<unfold>>";
            case DYNAMIC -> "<<dynamic>>";
            case FAILURE -> "<<fail>>";
        };
        return "rectangle \"" + node.label() + "\" " + stereotype + " as " + node.id();
    }

    private static String dotEdgeStyle(EdgeKind kind) {
        return switch (kind) {
            case SEQUENTIAL -> "color=\"#334455\"";
            case DYNAMIC -> "style=dashed, color=\"#775599\", label=\"depends on\"";
            case RECOVERY -> "style=dotted, color=\"#aa5544\", label=\"on failure\"";
            case LOOP -> "style=bold, color=\"#997722\", label=\"next\"";
        };
    }

    private static String umlArrow(EdgeKind kind) {
        return switch (kind) {
            case SEQUENTIAL -> " --> ";
            case DYNAMIC -> " ..> ";
            case RECOVERY -> " ..> ";
            case LOOP -> " -[#997722]-> ";
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }
}
