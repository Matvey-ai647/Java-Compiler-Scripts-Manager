import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

public class DiagnosticPrinter {

    public void printDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        if (diagnostics.isEmpty()) {
            System.out.println("No diagnostics available.");
            return;
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            String severity = getSeverity(diagnostic);
            String file = diagnostic.getSource() == null
                    ? "<compiler>" : diagnostic.getSource().getName();
            System.out.printf("%s [%s:%d:%d]: %s%n", severity, file, diagnostic.getLineNumber(),
                    diagnostic.getColumnNumber(), diagnostic.getMessage(null));
        }
    }

    private String getSeverity(Diagnostic<? extends JavaFileObject> diagnostic) {
        switch (diagnostic.getKind()) {
            case ERROR:
                return "ERROR";
            case WARNING:
                return "WARNING";
            case MANDATORY_WARNING:
                return "MANDATORY WARNING";
            default:
                return "MESSAGE";
        }
    }
}