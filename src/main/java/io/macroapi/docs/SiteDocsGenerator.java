package io.macroapi.docs;

import io.macroapi.demo.StorefrontApi;
import io.macroapi.demo.StorefrontMacros;
import io.macroapi.macro.MacroRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates the macro documentation that the Maven site publishes.
 *
 * <p>Bound to the {@code pre-site} phase, this writes one Markdown page describing every registered
 * macro and one diagram per macro, in both Graphviz DOT and PlantUML. Every byte of it is derived by
 * folding the macros' own plans through the documentation, cost and graph interpreters, so the
 * published description of a composite endpoint is the composition itself rather than a parallel
 * artefact that has to be kept in step by hand.</p>
 *
 * <p>No API call is made. Expansion is a syntactic operation, so the generator runs offline and the
 * sample requests in the registry may name resources that do not exist.</p>
 *
 * <p>Usage: {@code SiteDocsGenerator <output-site-directory>}, where the directory is the site
 * source root — {@code markdown/} and {@code resources/diagrams/} are created beneath it.</p>
 */
public final class SiteDocsGenerator {

    /** Not instantiable; entry is through {@link #main} or {@link #write}. */
    private SiteDocsGenerator() {
        throw new AssertionError("no instances");
    }

    /**
     * Writes the generated documentation.
     *
     * @param args a single argument: the site source directory to write into
     * @throws IOException if the output cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SiteDocsGenerator <output-site-directory>");
        }
        Path siteRoot = Path.of(args[0]);
        // The API is never called, only referenced, so the executor is closed immediately.
        MacroRegistry registry;
        try (StorefrontApi api = new StorefrontApi()) {
            registry = new StorefrontMacros(api).registry();
        }
        write(siteRoot, registry);
        System.out.println("[macro-docs] wrote " + registry.entries().size()
                + " macro page(s) and " + (registry.entries().size() * 2) + " diagram(s) to " + siteRoot);
    }

    /**
     * Writes the catalogue page and every diagram beneath a site source root.
     *
     * @param siteRoot the site source directory
     * @param registry the macros to document
     * @throws UncheckedIOException if writing fails
     */
    public static void write(Path siteRoot, MacroRegistry registry) {
        Path markdown = siteRoot.resolve("markdown");
        Path diagrams = siteRoot.resolve("resources").resolve("diagrams");
        try {
            Files.createDirectories(markdown);
            Files.createDirectories(diagrams);

            Map<String, String> dot = registry.dotDiagrams();
            Map<String, String> uml = registry.plantUmlDiagrams();
            for (Map.Entry<String, String> entry : dot.entrySet()) {
                Files.writeString(diagrams.resolve(entry.getKey() + ".dot"), entry.getValue());
            }
            for (Map.Entry<String, String> entry : uml.entrySet()) {
                Files.writeString(diagrams.resolve(entry.getKey() + ".puml"), entry.getValue());
            }
            Files.writeString(markdown.resolve("macros.md"), page(registry));
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to generate macro documentation", failure);
        }
    }

    /**
     * Builds the Markdown catalogue page: the registry's own macro descriptions, followed by the
     * per-macro dependency diagrams and the list of endpoints those graphs can reach.
     *
     * @param registry the macros to document
     * @return the complete Markdown page
     */
    private static String page(MacroRegistry registry) {
        StringBuilder out = new StringBuilder(registry.markdown("Macro catalogue"));
        out.append("\n## Call dependency diagrams\n\n");
        out.append("One diagram per macro, derived by folding its expansion through the graph ")
                .append("interpreter. Endpoint calls are rounded boxes, catamorphisms are chevrons, ")
                .append("recursive expansions are 3-D boxes, and a diamond marks a continuation that ")
                .append("could not be resolved statically. Dashed edges denote runtime dependence, ")
                .append("dotted edges recovery paths, and bold edges the back-edge of a loop.\n\n");
        for (MacroRegistry.Entry<?, ?> entry : registry.entries()) {
            out.append("### ").append(entry.name()).append(" (diagram)\n\n");
            out.append("Sources: [").append(entry.name()).append(".dot](diagrams/").append(entry.name())
                    .append(".dot) &middot; [").append(entry.name()).append(".puml](diagrams/")
                    .append(entry.name()).append(".puml)\n\n");
            out.append("```\n").append(entry.dot()).append("```\n\n");
        }
        out.append("## Reachable endpoints\n\n");
        out.append("Every low-level endpoint any registered macro can reach, derived from the graphs ")
                .append("rather than declared, so it cannot fall out of date.\n\n");
        registry.reachableEndpoints().forEach(name -> out.append("- `").append(name).append("`\n"));
        return out.toString();
    }
}
