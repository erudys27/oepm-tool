package oepm.manifest

import java.io.File

/**
 * Infers a package's package_name from its own .cls files' declared OO ABL
 * namespace (e.g. a file containing "class example.closer.Closer:" implies
 * "example.closer"), used to autofill openedge-project.json's package_name
 * when it's missing instead of requiring it to be hand-typed.
 *
 * Every .cls file under the source root is expected to agree on the same
 * namespace, per ADR-0002's one-namespace-per-package expectation. If none
 * are found, or they disagree, this fails loudly rather than guessing.
 */
object PackageNameInferrer {
    private val classDeclaration = Regex("""(?im)^\s*class\s+([A-Za-z_][\w.]*)\s*:""")

    fun infer(sourceDir: File): String {
        val namespaces =
            sourceDir
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("cls", ignoreCase = true) }
                .mapNotNull { file -> classDeclaration.find(file.readText())?.groupValues?.get(1) }
                .map { qualifiedClassName -> qualifiedClassName.substringBeforeLast('.') }
                .toSet()

        return when (namespaces.size) {
            0 ->
                throw IllegalStateException(
                    "Could not infer package_name: no .cls files with a recognizable " +
                        "\"class <namespace>.<Name>:\" declaration found under ${sourceDir.path}",
                )
            1 -> namespaces.single()
            else ->
                throw IllegalStateException(
                    "Could not infer package_name: .cls files under ${sourceDir.path} disagree on namespace " +
                        "(found ${namespaces.sorted()}) — set package_name explicitly in openedge-project.json instead",
                )
        }
    }
}
