package io.macroapi.interpret;

import java.util.List;
import java.util.Objects;

/**
 * A tree rendering of a plan's structure, produced by {@link OutlineAlgebra}.
 *
 * @param kind     the node category, for example {@code call} or {@code parallel}
 * @param detail   a short description of this node
 * @param children the nested descriptions, in plan order
 */
public record Outline(String kind, String detail, List<Outline> children) {

    /**
     * Canonical constructor; defensively copies the child list.
     *
     * @param kind     node category, non-null
     * @param detail   node description, non-null
     * @param children nested descriptions, copied
     */
    public Outline {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
        children = List.copyOf(children);
    }

    /**
     * A childless outline node.
     *
     * @param kind   node category
     * @param detail node description
     * @return the leaf
     */
    public static Outline leaf(String kind, String detail) {
        return new Outline(kind, detail, List.of());
    }

    /**
     * An outline node with children.
     *
     * @param kind     node category
     * @param detail   node description
     * @param children nested descriptions
     * @return the branch
     */
    public static Outline of(String kind, String detail, Outline... children) {
        return new Outline(kind, detail, List.of(children));
    }

    /**
     * Renders the outline as indented plain text using box-drawing characters.
     *
     * @return the multi-line rendering, without a trailing newline
     */
    public String render() {
        StringBuilder target = new StringBuilder();
        renderInto(target, "", true, true);
        return target.toString().stripTrailing();
    }

    /**
     * Renders the outline as a nested Markdown list, for embedding in generated documentation.
     *
     * @return the multi-line Markdown fragment
     */
    public String renderMarkdown() {
        StringBuilder target = new StringBuilder();
        renderMarkdownInto(target, 0);
        return target.toString().stripTrailing();
    }

    private void renderInto(StringBuilder target, String prefix, boolean last, boolean root) {
        if (root) {
            target.append(label()).append('\n');
        } else {
            target.append(prefix).append(last ? "'-- " : "|-- ").append(label()).append('\n');
        }
        String childPrefix = root ? "" : prefix + (last ? "    " : "|   ");
        for (int index = 0; index < children.size(); index++) {
            children.get(index).renderInto(target, childPrefix, index == children.size() - 1, false);
        }
    }

    private void renderMarkdownInto(StringBuilder target, int depth) {
        target.append("  ".repeat(depth)).append("- ").append(label()).append('\n');
        children.forEach(child -> child.renderMarkdownInto(target, depth + 1));
    }

    private String label() {
        return detail.isEmpty() ? kind : kind + ": " + detail;
    }
}
