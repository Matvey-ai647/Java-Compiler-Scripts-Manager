package JavaCompilerScriptsManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/**
 * A small Java source compiler and diagnostics tool.
 *
 * Build with: javac compiler.java
 * Run with:   java compiler
 */
public class compiler {
    private static final String VERSION = "3.17.0";
    private static final Path ANALYZER_RESULTS = Paths.get("compiler-analyzer-results.txt");
    private static final Path DEBUG_RESULTS = Paths.get("compiler-debug-results.txt");
    private static final Path SESSION_HISTORY = Paths.get(System.getProperty("user.home"),
            ".java-compiler-scripts-manager", "sessions.tsv");
    private static final DateTimeFormatter SESSION_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static boolean connected = true;

    public static void main(String[] args) {
        if (args.length == 0) {
            try {
                runShell(null);
            } catch (IOException e) {
                System.err.println("Ошибка ввода-вывода: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        int exitCode;
        try {
            exitCode = runCommand(args);
        } catch (IOException e) {
            System.err.println("Ошибка ввода-вывода: " + e.getMessage());
            exitCode = 2;
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void runShell(CompilerSession initialSession) throws IOException {
        CompilerSession currentSession = initialSession;
        if (currentSession != null) {
            printSessionCommands(currentSession);
        }
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Java Compiler Scripts Manager " + VERSION);
            System.out.println("Здравствуйте! С чего начнем?");
            System.out.println("Введите help для списка команд, exit для выхода.");

            while (true) {
                System.out.print(connected ? "java> " : "java(disconnected)> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                currentSession = currentSession == null ? new CompilerSession() : currentSession;
                currentSession.commands.add(line);
                String[] args;
                try {
                    args = splitCommand(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("Ошибка: " + e.getMessage());
                    continue;
                }
                if (args.length == 0) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(args[0]) || "quit".equalsIgnoreCase(args[0])) {
                    break;
                }
                if ("session".equalsIgnoreCase(args[0])) {
                    try {
                        if (args.length != 2) {
                            throw new IllegalArgumentException("использование: session <номер>");
                        }
                        int sessionNumber = Integer.parseInt(args[1]);
                        CompilerSession selectedSession = findSession(loadSessions(), sessionNumber);
                        if (selectedSession == null) {
                            throw new IllegalArgumentException("сеанс №" + sessionNumber + " не найден.");
                        }
                        if (currentSession.number != selectedSession.number) {
                            saveSession(currentSession);
                            currentSession = selectedSession;
                        }
                        printSessionCommands(currentSession);
                    } catch (IOException e) {
                        System.out.println("Ошибка ввода-вывода: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        System.out.println("Ошибка: " + e.getMessage());
                    }
                    continue;
                }
                try {
                    runCommand(args, scanner);
                } catch (IOException e) {
                    System.out.println("Ошибка ввода-вывода: " + e.getMessage());
                } catch (IllegalArgumentException e) {
                    System.out.println("Ошибка: " + e.getMessage());
                }
            }
        }
        if (currentSession == null) {
            currentSession = new CompilerSession();
        }
        saveSession(currentSession);
        System.out.println("Сеанс сохранен. История сеансов:");
        showSessionHistory();
    }

    private static String[] splitCommand(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                if (quoted) {
                    quoted = false;
                } else {
                    quoted = true;
                    quote = c;
                }
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("не закрыты кавычки в команде.");
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.toArray(String[]::new);
    }

    private static int runCommand(String[] args) throws IOException {
        return runCommand(args, null);
    }

    private static int runCommand(String[] args, Scanner scanner) throws IOException {
        String command = args[0].toLowerCase(Locale.ROOT);
        if ("help".equals(command) || "--help".equals(command) || "-h".equals(command)) {
            printHelp();
            return 0;
        }
        if ("ai".equals(command)) {
            if (args.length == 1) {
                if (scanner != null) {
                    runDukeAiChat(scanner);
                } else {
                    try (Scanner input = new Scanner(System.in)) {
                        runDukeAiChat(input);
                    }
                }
                return 0;
            }
            return runDukeAiQuestion(args);
        }
        if ("connect".equals(command)) {
            connected = true;
            System.out.println("Компилятор подключен.");
            return 0;
        }
        if ("disconnect".equals(command)) {
            connected = false;
            System.out.println("Компилятор отключен.");
            return 0;
        }
        if ("status".equals(command)) {
            System.out.println("Состояние: " + (connected ? "подключен" : "отключен"));
            System.out.println("Версия: " + VERSION);
            return 0;
        }
        if ("history".equals(command) || "sessions".equals(command)) {
            showSessionHistory();
            return 0;
        }
        if ("session".equals(command)) {
            if (args.length != 2) {
                throw new IllegalArgumentException("использование: java compiler session <номер>");
            }
            int sessionNumber;
            try {
                sessionNumber = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("номер сеанса должен быть целым числом.");
            }
            CompilerSession selectedSession = findSession(loadSessions(), sessionNumber);
            if (selectedSession == null) {
                throw new IllegalArgumentException("сеанс №" + sessionNumber + " не найден.");
            }
            runShell(selectedSession);
            return 0;
        }
        if ("packages".equals(command)) {
            return managePackages(args);
        }
        if ("results".equals(command) || "analyzer-results".equals(command)) {
            return showSavedResults(ANALYZER_RESULTS, "результаты анализаторов");
        }
        if ("debug-results".equals(command)) {
            return showSavedResults(DEBUG_RESULTS, "результаты Debug-отладки");
        }
        if ("analyzers".equals(command)) {
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            if (args.length != 2) {
                throw new IllegalArgumentException("использование: analyzers <файл.java>");
            }
            return runTechnicalAnalyzers(validateSource(args[1]));
        }
        if ("classify".equals(command)) {
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            if (args.length != 2) {
                throw new IllegalArgumentException("использование: classify <файл.java>");
            }
            return analyze(validateSource(args[1]), false, null, null, true);
        }
        if ("debug".equals(command)) {
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            if (args.length < 2) {
                throw new IllegalArgumentException("использование: debug <файл.java> [-- <аргументы программы>]");
            }
            int separator = -1;
            for (int i = 2; i < args.length; i++) {
                if ("--".equals(args[i])) {
                    separator = i;
                    break;
                }
            }
            if ((separator < 0 && args.length > 2) || separator > 2) {
                throw new IllegalArgumentException("аргументы запуска указываются после --.");
            }
            return runDebug(validateSource(args[1]),
                    separator < 0 ? new String[0] : Arrays.copyOfRange(args, separator + 1, args.length));
        }
        if (!"analyze".equals(command) && !"check".equals(command) && !"tree".equals(command)
                && !"compile".equals(command) && !"fix".equals(command)) {
            throw new IllegalArgumentException("неизвестная команда '" + args[0] + "'. Используйте help, для вызова справки");
        }
        if (!connected) {
            throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("укажите путь к Java-файлу.");
        }

        Path source = validateSource(args[1]);
        if ("fix".equals(command)) {
            formatSource(source);
            System.out.println("Безопасное форматирование применено: удалены пробелы в конце строк и добавлен финальный перевод строки.");
            return analyze(source, true, null);
        }
        if ("tree".equals(command)) {
            return analyze(source, true, null);
        }
        if ("compile".equals(command)) {
            Path outputDirectory = Paths.get("out").toAbsolutePath().normalize();
            for (int i = 2; i < args.length; i++) {
                if ("--out".equals(args[i]) && i + 1 < args.length) {
                    outputDirectory = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("неизвестный параметр: " + args[i]);
                }
            }
            Files.createDirectories(outputDirectory);
            return analyze(source, false, outputDirectory, null, true);
        }
        for (int i = 2; i < args.length; i++) {
            if (!"--tree".equals(args[i])) {
                throw new IllegalArgumentException("неизвестный параметр: " + args[i]);
            }
        }
        return analyze(source, hasOption(args, "--tree"), null);
    }

    private static Path validateSource(String fileName) {
        Path source = Paths.get(fileName).toAbsolutePath().normalize();
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("файл не найден: " + source);
        }
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
            throw new IllegalArgumentException("поддерживаются Java-файлы и Java-скрипты с расширением .java.");
        }
        return source;
    }

    private static int managePackages(String[] args) throws IOException {
        if (args.length == 1 || "list".equalsIgnoreCase(args[1]) || "status".equalsIgnoreCase(args[1])) {
            Path project = Paths.get(".").toAbsolutePath().normalize();
            System.out.println("Менеджеры Java-пакетов в проекте " + project + ":");
            printManagerFile(project, "pom.xml", "Maven");
            printManagerFile(project, "build.gradle", "Gradle");
            printManagerFile(project, "build.gradle.kts", "Gradle Kotlin DSL");
            printManagerFile(project, "mvnw", "Maven Wrapper");
            printManagerFile(project, "mvnw.cmd", "Maven Wrapper (Windows)");
            printManagerFile(project, "gradlew", "Gradle Wrapper");
            printManagerFile(project, "gradlew.bat", "Gradle Wrapper (Windows)");
            System.out.println("Команды: packages maven <аргументы Maven> | packages gradle <аргументы Gradle>");
            return 0;
        }

        String manager = args[1].toLowerCase(Locale.ROOT);
        if (!"maven".equals(manager) && !"gradle".equals(manager)) {
            throw new IllegalArgumentException("использование: packages [list|status|maven|gradle] [аргументы менеджера]");
        }
        if (args.length < 3) {
            throw new IllegalArgumentException("укажите команду менеджера, например packages maven dependency:tree.");
        }

        List<String> command = new ArrayList<>();
        command.add("maven".equals(manager) ? "mvn" : "gradle");
        command.addAll(Arrays.asList(args).subList(2, args.length));
        System.out.println("Запуск менеджера пакетов: " + manager);
        Process process = new ProcessBuilder(command).inheritIO().start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ожидание команды менеджера пакетов было прервано.", e);
        }
    }

    private static void printManagerFile(Path project, String fileName, String label) {
        Path managerFile = project.resolve(fileName);
        if (Files.exists(managerFile)) {
            System.out.println("  " + label + ": " + managerFile);
        }
    }

    private static int showSavedResults(Path resultsFile, String title) throws IOException {
        if (!Files.isRegularFile(resultsFile)) {
            System.out.println("Сохраненные " + title + " не найдены. Сначала выполните соответствующую команду.");
            return 1;
        }
        System.out.println(new String(Files.readAllBytes(resultsFile), StandardCharsets.UTF_8));
        return 0;
    }

    private static int runTechnicalAnalyzers(Path source) throws IOException {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            System.err.println("Не найден компилятор JDK. Установите JDK и проверьте PATH.");
            return 2;
        }

        String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\uFEFF", "");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        TechnicalMetricsScanner metrics = new TechnicalMetricsScanner();
        try {
            JavaFileObject sourceObject = createSourceFileObject(source);
            List<String> options = Arrays.asList("-proc:none", "-Xlint:all", "-encoding", "UTF-8");
            JavacTask task = (JavacTask) javaCompiler.getTask(
                    null, fileManager, diagnostics, options, null, Arrays.asList(sourceObject));
            for (CompilationUnitTree unit : task.parse()) {
                metrics.scan(unit, null);
            }
            task.analyze();
        } finally {
            fileManager.close();
        }

        StringBuilder report = new StringBuilder();
        report.append("Технический анализ Java-файла: ").append(source).append('\n');
        report.append("Время анализа: ").append(Instant.now()).append('\n');
        report.append("Размер: ").append(Files.size(source)).append(" байт\n");
        report.append("Строки: ").append(countSourceLines(sourceText)).append('\n');
        report.append("Импорты: ").append(metrics.imports).append('\n');
        report.append("Классы/интерфейсы/перечисления: ").append(metrics.classes).append('\n');
        report.append("Методы и конструкторы: ").append(metrics.methods).append('\n');
        report.append("Объявления переменных: ").append(metrics.variables).append('\n');
        report.append("Условия и ветвления: ").append(metrics.branches).append('\n');
        report.append("Циклы: ").append(metrics.loops).append('\n');
        report.append("Вызовы методов: ").append(metrics.invocations).append('\n');
        report.append("Маркеры TODO/FIXME: ").append(countMatches(sourceText, "\\b(?:TODO|FIXME)\\b")).append('\n');
        report.append("Диагностика компилятора:\n");
        report.append(formatDiagnostics(diagnostics.getDiagnostics()));
        report.append("Уровень ветвления (приближенно): ").append(metrics.branches + metrics.loops).append('\n');
        report.append("Примечание: показатели структуры вычисляются по AST; анализ не заменяет тестирование и ревью.\n");

        Files.write(ANALYZER_RESULTS, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report.toString());
        System.out.println("Результат сохранен в: " + ANALYZER_RESULTS.toAbsolutePath().normalize());
        return countErrors(diagnostics) == 0 ? 0 : 1;
    }

    private static int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countSourceLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            } else if (text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                lines++;
            }
        }
        char last = text.charAt(text.length() - 1);
        if (last != '\n' && last != '\r') {
            lines++;
        }
        return lines;
    }

    private static int runDebug(Path source, String[] programArgs) throws IOException {
        Path outputDirectory = Paths.get("out", "debug").toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        System.out.println("Начинается сборка, проверка синтаксиса и компиляция перед запуском Debug...");
        StringBuilder diagnosticsReport = new StringBuilder();
        if (analyze(source, false, outputDirectory, diagnosticsReport, true) != 0) {
            String report = "Debug не запущен. Возможно сборка, проверка синтаксиса или компиляция завершилась ошибкой.\n"
                    + diagnosticsReport.toString()
                    + "Рекомендация: исправьте ошибки выше и повторите команду debug.\n";
            Files.write(DEBUG_RESULTS, report.getBytes(StandardCharsets.UTF_8));
            System.out.println("Debug не запущен. Подробные причины и советы указаны выше и сохранены в "
                    + DEBUG_RESULTS.toAbsolutePath().normalize());
            return 1;
        }

        String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\uFEFF", "");
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(sourceText);
        String className = source.getFileName().toString();
        className = className.substring(0, className.length() - ".java".length());
        if (packageMatcher.find()) {
            className = packageMatcher.group(1) + "." + className;
        }

        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-ea");
        command.add("-cp");
        command.add(outputDirectory.toString());
        command.add(className);
        command.addAll(Arrays.asList(programArgs));

        System.out.println("Запускается откладка...");
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            String report = """
                    Debug не запущен: не удалось запустить Java-процесс.
                    Причина: %s
                    Совет: проверьте наличие JDK/JRE, доступность java и корректность собранного класса.
                    """.formatted(e.getMessage());
            Files.write(DEBUG_RESULTS, report.getBytes(StandardCharsets.UTF_8));
            System.err.print(report);
            return 1;
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ожидание Debug-запуска было прервано.", e);
        }

        StringBuilder report = new StringBuilder();
        report.append("Результаты Debug-отладки\n");
        report.append("Файл: ").append(source).append('\n');
        report.append("Класс: ").append(className).append('\n');
        report.append("Начало запуска: ").append(started).append('\n');
        report.append("Длительность: ").append(TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startNanos)).append(" мс\n");
        report.append("Статус: процесс завершен\n");
        report.append("Код завершения: ").append(exitCode).append('\n');
        report.append("Вывод программы передавался напрямую в терминал.\n");
        Files.write(DEBUG_RESULTS, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report.toString());
        System.out.println("Полный отчет сохранен в: " + DEBUG_RESULTS.toAbsolutePath().normalize());
        return exitCode == 0 ? 0 : 1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class TechnicalMetricsScanner extends TreeScanner<Void, Void> {
        private int imports;
        private int classes;
        private int methods;
        private int variables;
        private int branches;
        private int loops;
        private int invocations;

        @Override
        public Void visitImport(com.sun.source.tree.ImportTree tree, Void unused) {
            imports++;
            return super.visitImport(tree, unused);
        }

        @Override
        public Void visitClass(com.sun.source.tree.ClassTree tree, Void unused) {
            classes++;
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitMethod(com.sun.source.tree.MethodTree tree, Void unused) {
            methods++;
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(com.sun.source.tree.VariableTree tree, Void unused) {
            variables++;
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitIf(com.sun.source.tree.IfTree tree, Void unused) {
            branches++;
            return super.visitIf(tree, unused);
        }

        @Override
        public Void visitSwitch(com.sun.source.tree.SwitchTree tree, Void unused) {
            branches++;
            return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitSwitchExpression(com.sun.source.tree.SwitchExpressionTree tree, Void unused) {
            branches++;
            return super.visitSwitchExpression(tree, unused);
        }

        @Override
        public Void visitConditionalExpression(com.sun.source.tree.ConditionalExpressionTree tree, Void unused) {
            branches++;
            return super.visitConditionalExpression(tree, unused);
        }

        @Override
        public Void visitForLoop(com.sun.source.tree.ForLoopTree tree, Void unused) {
            loops++;
            return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(com.sun.source.tree.EnhancedForLoopTree tree, Void unused) {
            loops++;
            return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitWhileLoop(com.sun.source.tree.WhileLoopTree tree, Void unused) {
            loops++;
            return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(com.sun.source.tree.DoWhileLoopTree tree, Void unused) {
            loops++;
            return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree tree, Void unused) {
            invocations++;
            return super.visitMethodInvocation(tree, unused);
        }
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int analyze(Path source, boolean showTree, Path outputDirectory) throws IOException {
        return analyze(source, showTree, outputDirectory, null, false);
    }

    private static int analyze(Path source, boolean showTree, Path outputDirectory,
                               StringBuilder capturedDiagnostics, boolean showClassifications) throws IOException {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            System.err.println("Не удалось найти JDK. Попробуйте установить  JDK и проверьте PATH.");
            return 2;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject unit = createSourceFileObject(source);
            Iterable<? extends JavaFileObject> units = Arrays.asList(unit);
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("-Xlint:all");
            options.add("-encoding");
            options.add("UTF-8");
            if (outputDirectory != null) {
                options.add("-d");
                options.add(outputDirectory.toString());
            }

            JavacTask task = (JavacTask) javaCompiler.getTask(
                    null, fileManager, diagnostics, options, null, units);
            List<CompilationUnitTree> parsedUnits = new ArrayList<>();
            for (CompilationUnitTree parsedUnit : task.parse()) {
                parsedUnits.add(parsedUnit);
            }
            if (showTree) {
                Trees trees = Trees.instance(task);
                for (CompilationUnitTree parsedUnit : parsedUnits) {
                    System.out.println("Структурное дерево: " + source.getFileName());
                    new AstPrinter(parsedUnit, trees.getSourcePositions()).scan(parsedUnit, 0);
                }
            }
            task.analyze();
            if (showClassifications && countErrors(diagnostics) == 0) {
                Trees trees = Trees.instance(task);
                for (CompilationUnitTree parsedUnit : parsedUnits) {
                    TypeClassificationScanner classifier = new TypeClassificationScanner(parsedUnit, trees);
                    classifier.scan(parsedUnit, null);
                    System.out.print(classifier.getClassifications());
                }
            }
            if (outputDirectory != null && countErrors(diagnostics) == 0) {
                task.generate();
            }
        }

        String formattedDiagnostics = formatDiagnostics(diagnostics.getDiagnostics());
        System.out.print(formattedDiagnostics);
        if (capturedDiagnostics != null) {
            capturedDiagnostics.append(formattedDiagnostics);
        }
        long errors = countErrors(diagnostics);
        if (errors == 0) {
            System.out.println(outputDirectory == null
                    ? "Проверка завершена: ошибок компиляции не найдено."
                    : "Компиляция завершена. Классы сохранены в: " + outputDirectory);
            return 0;
        }
        System.out.println("Проверка завершена: ошибок: " + errors + ".");
        return 1;
    }

    private static JavaFileObject createSourceFileObject(Path source) throws IOException {
        final String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final String normalized = sourceText.replace("\uFEFF", "");
        return new SimpleJavaFileObject(source.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return normalized;
            }
        };
    }

    private static long countErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        long count = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                count++;
            }
        }
        return count;
    }

    private static String formatDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder report = new StringBuilder();
        if (diagnostics.isEmpty()) {
            return "Диагностика: замечаний нет.\n";
        }
        long errors = 0;
        long warnings = 0;
        long messages = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            String severity;
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                severity = "ОШИБКА";
                errors++;
            } else if (diagnostic.getKind() == Diagnostic.Kind.WARNING
                    || diagnostic.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                severity = "ПРЕДУПРЕЖДЕНИЕ";
                warnings++;
            } else {
                severity = "СООБЩЕНИЕ";
                messages++;
            }
            String file = diagnostic.getSource() == null
                    ? "<компилятор>" : Paths.get(diagnostic.getSource().toUri()).getFileName().toString();
            report.append(severity).append(" [").append(file).append(":")
                    .append(diagnostic.getLineNumber()).append(":").append(diagnostic.getColumnNumber())
                    .append("]: ").append(diagnostic.getMessage(Locale.forLanguageTag("ru"))).append('\n');
            String advice = recommendation(diagnostic.getMessage(Locale.ROOT));
            if (advice.isEmpty()) {
                advice = diagnostic.getKind() == Diagnostic.Kind.ERROR
                        ? "Проверьте указанную строку и ближайший к ней код; сверьте сообщение компилятора с объявлением, типами и импортами."
                        : "Проверьте, не указывает ли предупреждение на потенциальную ошибку или устаревший код.";
            }
            report.append("  Совет: ").append(advice).append('\n');
        }
        report.append("Итого диагностики: ошибок: ").append(errors)
                .append(", предупреждений: ").append(warnings)
                .append(", сообщений: ").append(messages).append(".\n");
        return report.toString();
    }

    private static String recommendation(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case String text when text.contains("cannot find symbol") ->
                    "Проверьте написание имени, объявление переменной/метода и необходимые import.";
            case String text when text.contains("';' expected") ->
                    "Добавьте точку с запятой в указанном месте или проверьте предыдущую строку.";
            case String text when text.contains("')' expected") || text.contains("'}' expected")
                    || text.contains("']' expected") ->
                    "Добавьте пропущенную закрывающую скобку и проверьте парность скобок в этом выражении.";
            case String text when text.contains("reached end of file while parsing") ->
                    "Проверьте парные фигурные скобки и завершенность последнего выражения.";
            case String text when text.contains("illegal start") || text.contains("not a statement") ->
                    "Проверьте синтаксис в этой и предыдущей строках: часто причина — лишний или пропущенный знак.";
            case String text when text.contains("incompatible types") ->
                    "Сверьте типы присваивания и возвращаемого значения; при необходимости выполните явное преобразование.";
            case String text when text.contains("does not exist") ->
                    "Проверьте имя пакета или класса, import и наличие зависимости в classpath.";
            case String text when text.contains("no suitable method") || text.contains("cannot be applied") ->
                    "Сверьте количество и типы аргументов с объявлением метода и доступными перегрузками.";
            case String text when text.contains("has private access") || text.contains("is not public") ->
                    "Проверьте модификатор доступа и используйте доступный публичный API.";
            case String text when text.contains("unreported exception") ->
                    "Обработайте исключение блоком try/catch либо добавьте его в объявление throws.";
            case String text when text.contains("duplicate class") ->
                    "Проверьте имя класса и package: в одном пакете не должно быть повторных объявлений этого класса.";
            case String text when text.contains("should be declared in a file named") ->
                    "Переименуйте файл так, чтобы его имя совпадало с именем public-класса.";
            case String text when text.contains("cannot find class file") ->
                    "Проверьте classpath и убедитесь, что необходимые классы собраны и доступны компилятору.";
            case String text when text.contains("is never used") || text.contains("is not used") ->
                    "Удалите неиспользуемое объявление либо используйте его по назначению.";
            case String text when text.contains("might not have been initialized") ->
                    "Присвойте переменной значение при объявлении или во всех ветвях до ее использования.";
            case String text when text.contains("deprecated") ->
                    "Замените устаревший API на рекомендованный аналог из документации Java.";
            case String text when text.contains("unchecked") || text.contains("unsafe operations") ->
                    "Проверьте параметры типов и добавьте корректную типизацию вместо raw types.";
            default -> "";
        };
    }

    private static void formatSource(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder formatted = new StringBuilder();
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        for (int i = 0; i < lineCount; i++) {
            formatted.append(lines[i].replaceAll("[\\t ]+$", ""));
            formatted.append(newline);
        }
        if (lineCount == 0) {
            formatted.append(newline);
        }
        Files.write(source, formatted.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void printHelp() {
        System.out.println("Java Compiler Scripts Manager " + VERSION);
        System.out.println("Добро пожаловать в компилятор Java Compiler Scripts Manager. Ознакомьтесь как использавать этот компилятор");
        System.out.println("Использование:");
        System.out.println("  java compiler                         интерактивный режим");
        System.out.println("  java compiler ai                      Duke AI Assistant, интерактивный чат");
        System.out.println("  java compiler ai <вопрос>             задать вопрос локальной модели");
        System.out.println("  java compiler ai fix <файл.java>      получить советы по исправлению ошибок");
        System.out.println("  java compiler check <файл.java>       проверить синтаксис и показать диагностику");
        System.out.println("  java compiler analyze <файл.java> [--tree]  проверить и показать ошибки/советы");
        System.out.println("  java compiler tree <файл.java>        показать структурное дерево и диагностику");
        System.out.println("  java compiler compile <файл.java> [--out <каталог>]  скомпилировать");
        System.out.println("  java compiler fix <файл.java>         применить безопасное форматирование и проверить");
        System.out.println("  java compiler analyzers <файл.java>    технический AST-анализ, метрики и диагностика");
        System.out.println("  java compiler classify <файл.java>     определить типы переменных, методов и типов данных");
        System.out.println("  java compiler results                  показать последние результаты анализаторов");
        System.out.println("  java compiler packages [list]           обнаружить Maven/Gradle и wrapper-файлы");
        System.out.println("  java compiler packages maven <команда>  запустить Maven");
        System.out.println("  java compiler packages gradle <команда> запустить Gradle");
        System.out.println("  java compiler debug <файл.java> [-- аргументы]  проверить, скомпилировать и запустить консольную/Swing-программу");
        System.out.println("  java compiler debug-results             показать последний отчет Debug-запуска");
        System.out.println("  java compiler history                    показать историю сеансов компилятора");
        System.out.println("  java JavaCompilerScriptsManager.compiler session <номер>  открыть сеанс и показать введенные команды");
        System.out.println("  connect | disconnect | status | help | history | session <номер> | exit  команды интерактивного режима");
        System.out.println("Требуется JDK. Проверяются исходники и скрипты Java с расширением .java.");
        System.out.println("Еще в компиляторе появился ИИ-помощник Duke AI Assistant, который использует локальную модель Ollama. Она не требует платной подписки и работает абсолютно бесплатно. Вы можете задавать вопросы по Java, компиляции, исправлению ошибок, улучшению кода и другое. Для работы с Duke AI Assistant установите локальную модель Ollama. Для этого выполните команду в терминале: ollama pull qwen2.5-coder:7b.");
        System.out.println("Настройки: переменные DUKE_AI_MODEL и DUKE_AI_ENDPOINT.");
        System.out.println("Хотите знать больше о нашем компиляторе? Перейдите по ссылке на наш исходный код GitHub компилятора Java");
        System.out.println("----ССЫЛКА НА НАШ РЕПОЗИТОРИЙ GITHUB----");
        System.out.println("https://github.com/Matvey-ai647/Java-Compiler-Scripts-Manager");
        System.out.println("----ССЫЛКА НА ИСХОДНЫЙ КОД КОМПИЛЯТОРА JAVA----");
        System.out.println("https://github.com/Matvey-ai647/Java-Compiler-Scripts-Manager/blob/main/compiler.java");
        System.out.println("Посетите наш официальный сайт Java Compiler Scripts Manager");
        System.out.println("----НАШ ОФИЦИАЛЬНЫЙ САЙТ----");
        System.out.println("https://sites.google.com/view/javacompilerscriptsmanager/%D0%B3%D0%BB%D0%B0%D0%B2%D0%BD%D0%B0%D1%8F");
        System.out.println("----Помогайте нам с компилятором----");
        System.out.println("Привет, мы команда Java Compiler. И мы хотим чтобы наш компилятор стал полезным и профессиональным для удобства с работой Java. Если вы хотите нам помочь, оставьте нам комментарий к нашему исходному коду GitHub. Спасибо!");
    }

    private static void runDukeAiChat(Scanner scanner) throws IOException {
        List<AssistantMessage> conversation = new ArrayList<>();
        conversation.add(new AssistantMessage("system",
                "Ты — Duke AI Assistant, помощник по Java, компиляции и исправлению ошибок. "
                        + "Отвечай по-русски, объясняй причины ошибок и предлагай безопасные исправления. "
                        + "Не утверждай, что изменил файлы: только предлагай изменения."));
        conversation.add(new AssistantMessage("assistant", "Привет! Чем я могу помочь?"));

        System.out.println("Duke AI Assistant");
        System.out.println("Привет! Чем я могу помочь?");
        System.out.println("Введите /exit для завершения чата.");
        while (true) {
            System.out.print("Вы: ");
            if (!scanner.hasNextLine()) {
                return;
            }
            String prompt = scanner.nextLine().trim();
            if (prompt.isEmpty()) {
                continue;
            }
            if ("/exit".equalsIgnoreCase(prompt) || "exit".equalsIgnoreCase(prompt)) {
                return;
            }
            conversation.add(new AssistantMessage("user", prompt));
            try {
                String response = requestDukeAi(conversation);
                conversation.add(new AssistantMessage("assistant", response));
                System.out.println("Duke AI Assistant: " + response);
            } catch (IOException e) {
                conversation.remove(conversation.size() - 1);
                System.out.println("Не удалось получить ответ Duke AI Assistant: " + e.getMessage());
            }
        }
    }

    private static int runDukeAiQuestion(String[] args) throws IOException {
        String prompt;
        if ("fix".equalsIgnoreCase(args[1])) {
            if (args.length != 3) {
                throw new IllegalArgumentException("использование: ai fix <файл.java>");
            }
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            Path source = validateSource(args[2]);
            StringBuilder diagnostics = new StringBuilder();
            analyze(source, false, null, diagnostics, false);
            String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            prompt = "Проанализируй Java-файл и помоги исправить обнаруженные проблемы. "
                    + "Сначала перечисли причины ошибок компиляции, затем предложи точечные изменения "
                    + "или исправленный код. Не утверждай, что файл уже изменен.\n"
                    + "Файл: " + source + "\nДиагностика компилятора:\n" + diagnostics
                    + "\nИсходный код:\n```java\n" + sourceText + "\n```";
        } else {
            prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }

        List<AssistantMessage> conversation = Arrays.asList(
                new AssistantMessage("system",
                        "Ты — Duke AI Assistant, помощник по Java, компиляции, исправлению ошибок, улучшению кода, рефакторингу и оптимизации а также по Maven и Gradle и их командам. Давай советы и рекомендации по улучшению кода, исправлению ошибок и оптимизации."
                                + "Отвечай по-русски, кратко и точно. Не утверждай, что изменил файлы."),
                new AssistantMessage("user", prompt));
        System.out.println("Duke AI Assistant:");
        System.out.println(requestDukeAi(conversation));
        return 0;
    }

    private static String requestDukeAi(List<AssistantMessage> messages) throws IOException {
        String model = System.getenv().getOrDefault("DUKE_AI_MODEL", "qwen2.5-coder:7b");
        String endpoint = System.getenv().getOrDefault(
                "DUKE_AI_ENDPOINT", "http://localhost:11434/api/chat");
        StringBuilder payload = new StringBuilder("{\"model\":")
                .append(jsonString(model))
                .append(",\"stream\":false,\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                payload.append(',');
            }
            AssistantMessage message = messages.get(i);
            payload.append("{\"role\":").append(jsonString(message.role))
                    .append(",\"content\":").append(jsonString(message.content)).append('}');
        }
        payload.append("]}");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ожидание ответа Ollama было прервано.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("некорректный адрес DUKE_AI_ENDPOINT: " + endpoint, e);
        }
        String responseBody = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = jsonStringProperty(responseBody, "error");
            throw new IOException("Ollama вернула HTTP " + response.statusCode()
                    + (detail == null ? "." : ": " + detail));
        }
        String answer = jsonStringProperty(responseBody, "content");
        if (answer == null || answer.isBlank()) {
            String detail = jsonStringProperty(responseBody, "error");
            throw new IOException(detail == null
                    ? "Ollama вернула пустой или непонятный ответ."
                    : detail);
        }
        return answer;
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String jsonStringProperty(String json, String property) throws IOException {
        String key = "\"" + property + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int cursor = keyIndex + key.length();
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= json.length() || json.charAt(cursor++) != ':') {
            return null;
        }
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= json.length() || json.charAt(cursor++) != '"') {
            return null;
        }

        StringBuilder value = new StringBuilder();
        while (cursor < json.length()) {
            char character = json.charAt(cursor++);
            if (character == '"') {
                return value.toString();
            }
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (cursor >= json.length()) {
                break;
            }
            char escaped = json.charAt(cursor++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (cursor + 4 > json.length()) {
                        throw new IOException("Ollama вернула некорректную JSON-строку.");
                    }
                    try {
                        value.append((char) Integer.parseInt(json.substring(cursor, cursor + 4), 16));
                    } catch (NumberFormatException e) {
                        throw new IOException("Ollama вернула некорректную JSON-строку.", e);
                    }
                    cursor += 4;
                }
                default -> throw new IOException("Ollama вернула некорректную JSON-строку.");
            }
        }
        throw new IOException("Ollama вернула незавершенную JSON-строку.");
    }

    private static final class AssistantMessage {
        private final String role;
        private final String content;

        private AssistantMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static List<CompilerSession> loadSessions() throws IOException {
        List<CompilerSession> sessions = new ArrayList<>();
        if (!Files.exists(SESSION_HISTORY)) {
            return sessions;
        }
        if (!Files.isRegularFile(SESSION_HISTORY)) {
            throw new IOException("путь истории сеансов не является файлом: " + SESSION_HISTORY);
        }

        List<String> lines = Files.readAllLines(SESSION_HISTORY, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\t", -1);
            if (fields.length != 4) {
                throw new IOException("повреждена запись истории сеансов в строке " + (i + 1) + ".");
            }
            try {
                int number = Integer.parseInt(fields[0]);
                Instant usedAt = Instant.parse(fields[1]);
                String title = decodeSessionField(fields[2]);
                List<String> commands = new ArrayList<>();
                if (!fields[3].isEmpty()) {
                    for (String encodedCommand : fields[3].split(",", -1)) {
                        commands.add(decodeSessionField(encodedCommand));
                    }
                }
                sessions.add(new CompilerSession(number, title, usedAt, commands));
            } catch (IllegalArgumentException e) {
                throw new IOException("повреждена запись истории сеансов в строке " + (i + 1) + ".", e);
            }
        }
        return sessions;
    }

    private static void saveSession(CompilerSession session) throws IOException {
        List<CompilerSession> sessions = loadSessions();
        CompilerSession existingSession = findSession(sessions, session.number);
        if (session.number == 0) {
            int nextNumber = 1;
            for (CompilerSession savedSession : sessions) {
                nextNumber = Math.max(nextNumber, savedSession.number + 1);
            }
            session.number = nextNumber;
            session.title = "Сеанс компилятора №" + nextNumber;
            sessions.add(session);
        } else if (existingSession == null) {
            throw new IOException("не удалось найти сохраняемый сеанс №" + session.number + ".");
        } else {
            int index = sessions.indexOf(existingSession);
            sessions.set(index, session);
        }
        session.usedAt = Instant.now();

        Path parent = SESSION_HISTORY.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "compiler-sessions-", ".tmp");
        try {
            List<String> lines = new ArrayList<>();
            for (CompilerSession savedSession : sessions) {
                StringBuilder line = new StringBuilder();
                line.append(savedSession.number).append('\t')
                        .append(savedSession.usedAt).append('\t')
                        .append(encodeSessionField(savedSession.title)).append('\t');
                for (int i = 0; i < savedSession.commands.size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    line.append(encodeSessionField(savedSession.commands.get(i)));
                }
                lines.add(line.toString());
            }
            Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, SESSION_HISTORY, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, SESSION_HISTORY, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String encodeSessionField(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSessionField(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static CompilerSession findSession(List<CompilerSession> sessions, int number) {
        for (CompilerSession session : sessions) {
            if (session.number == number) {
                return session;
            }
        }
        return null;
    }

    private static void showSessionHistory() throws IOException {
        List<CompilerSession> sessions = loadSessions();
        if (sessions.isEmpty()) {
            System.out.println("Нет сеансов. Чтобы добавить первый сеанс, начните работать с компилятором. После выхода из компилятора, ваш сеанс использования компилятора сохранится и отобразится в истории сеансов использования компилятора");
            return;
        }
        sessions.sort((left, right) -> Integer.compare(right.number, left.number));
        System.out.printf("%-5s %-30s %-15s %-20s %s%n",
                "№", "Название сеанса", "Номер сеанса", "Дата и время", "Команда");
        for (int i = 0; i < sessions.size(); i++) {
            CompilerSession session = sessions.get(i);
            System.out.printf("%-5d %-30s %-15d %-20s java JavaCompilerScriptsManager.compiler session %d%n",
                    i + 1, session.title, session.number, SESSION_DATE_FORMAT.format(session.usedAt), session.number);
        }
    }

    private static void printSessionCommands(CompilerSession session) {
        System.out.println("Команды сеанса «" + session.title + "» №" + session.number + ":");
        if (session.commands.isEmpty()) {
            System.out.println("В этом сеансе команды еще не вводились.");
            return;
        }
        for (int i = 0; i < session.commands.size(); i++) {
            System.out.println((i + 1) + ". " + session.commands.get(i));
        }
    }

    private static final class CompilerSession {
        private int number;
        private String title;
        private Instant usedAt;
        private final List<String> commands;

        private CompilerSession() {
            this(0, "", Instant.now(), new ArrayList<>());
        }

        private CompilerSession(int number, String title, Instant usedAt, List<String> commands) {
            this.number = number;
            this.title = title;
            this.usedAt = usedAt;
            this.commands = commands;
        }
    }

    private static final class TypeClassificationScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final SourcePositions positions;
        private final StringBuilder classifications = new StringBuilder();

        private TypeClassificationScanner(CompilationUnitTree unit, Trees trees) {
            this.unit = unit;
            this.trees = trees;
            this.positions = trees.getSourcePositions();
        }

        @Override
        public Void visitClass(com.sun.source.tree.ClassTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof TypeElement type) {
                String kind = switch (tree.getKind()) {
                    case CLASS -> "класс";
                    case INTERFACE -> "интерфейс";
                    case ENUM -> "перечисление";
                    case ANNOTATION_TYPE -> "аннотация";
                    case RECORD -> "record";
                    default -> tree.getKind().name();
                };
                append("Тип данных (" + kind + ")", type.getQualifiedName().toString(),
                        type.asType().toString(), tree);
            }
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitMethod(com.sun.source.tree.MethodTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
                boolean constructor = method.getKind() == ElementKind.CONSTRUCTOR;
                StringBuilder signature = new StringBuilder();
                if (constructor) {
                    Element owner = method.getEnclosingElement();
                    signature.append(owner.getSimpleName());
                } else {
                    signature.append(method.getSimpleName());
                }
                signature.append('(');
                for (int i = 0; i < method.getParameters().size(); i++) {
                    if (i > 0) {
                        signature.append(", ");
                    }
                    signature.append(method.getParameters().get(i).asType());
                }
                signature.append(')');
                if (!constructor) {
                    signature.append(" -> ").append(method.getReturnType());
                }
                append(constructor ? "Конструктор" : "Функция", signature.toString(), "", tree);
            }
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(com.sun.source.tree.VariableTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof VariableElement variable) {
                append(variableKind(variable.getKind()), variable.getSimpleName().toString(),
                        variable.asType().toString(), tree);
            }
            return super.visitVariable(tree, unused);
        }

        private String variableKind(ElementKind kind) {
            if (kind == ElementKind.FIELD) {
                return "Поле";
            }
            if (kind == ElementKind.PARAMETER) {
                return "Параметр";
            }
            if (kind == ElementKind.LOCAL_VARIABLE) {
                return "Локальная переменная";
            }
            if (kind == ElementKind.ENUM_CONSTANT) {
                return "Константа перечисления";
            }
            return "Переменная";
        }

        private void append(String category, String name, String type, Tree tree) {
            long start = positions.getStartPosition(tree);
            long line = start < 0 ? -1 : unit.getLineMap().getLineNumber(start);
            classifications.append(category).append(": ").append(name);
            if (!type.isEmpty()) {
                classifications.append(" -> ").append(type);
            }
            if (line > 0) {
                classifications.append(" [строка ").append(line).append(']');
            }
            classifications.append('\n');
        }

        private String getClassifications() {
            return classifications.toString();
        }
    }

    private static final class AstPrinter extends TreeScanner<Void, Integer> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;

        private AstPrinter(CompilationUnitTree unit, SourcePositions positions) {
            this.unit = unit;
            this.positions = positions;
        }

        @Override
        public Void scan(Tree tree, Integer depth) {
            if (tree == null) {
                return null;
            }
            int currentDepth = depth == null ? 0 : depth;
            for (int i = 0; i < currentDepth; i++) {
                System.out.print("  ");
            }
            long start = positions.getStartPosition(tree);
            long line = start < 0 ? -1 : unit.getLineMap().getLineNumber(start);
            System.out.println(tree.getKind() + (line > 0 ? " (строка " + line + ")" : ""));
            return super.scan(tree, currentDepth + 1);
        }
    }
}
