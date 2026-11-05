import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class CompilerManager {
    private final JavaCompiler compiler;

    public CompilerManager() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
    }

    public void compile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        List<String> options = List.of("-d", "out");
        Iterable<? extends javax.tools.JavaFileObject> compilationUnits = compiler.getStandardFileManager(null, null, null).getJavaFileObjects(file);
        boolean success = compiler.getTask(null, null, null, options, null, compilationUnits).call();

        if (success) {
            System.out.println("Compilation successful: " + filePath);
        } else {
            System.out.println("Compilation failed: " + filePath);
        }
    }

    public void compileAndRun(String filePath) throws IOException {
        compile(filePath);
        String className = getClassName(filePath);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("java", "-cp", "out", className);
            processBuilder.inheritIO();
            Process process = processBuilder.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Error running the compiled class: " + e.getMessage());
        }
    }

    private String getClassName(String filePath) {
        String fileName = new File(filePath).getName();
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }
}