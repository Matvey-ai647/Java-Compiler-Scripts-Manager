import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class installer {
    private static final String INSTALL_COMMAND = "javac start install";
    private static final String INSTALL_DIRECTORY = ".java-compiler-scripts-manager";

    public static void main(String[] args) {
        System.out.println("Добро пожаловать в инсталлятор Java Compiler Scripts Manager Setup Installer.");
        System.out.println("Если вы готовы к установке, введите команду \"javac  start install\"");
        System.out.println("Для запуска мастера: javac installer.java, затем java installer");
        System.out.print("> ");

        Scanner scanner = new Scanner(System.in);
        if (!scanner.hasNextLine()) {
            return;
        }
        String command = scanner.nextLine().trim().replaceAll("\\s+", " ");
        if (!INSTALL_COMMAND.equalsIgnoreCase(command)) {
            System.out.println("Установка не запущена. Введите команду: " + INSTALL_COMMAND);
            return;
        }

        try {
            install();
        } catch (InstallFailure e) {
            printError(e.code, e.title, e.reason, e.fix);
        } catch (AccessDeniedException e) {
            printError("JCSM-104", "Недостаточно прав", e.getMessage(),
                    "Выберите доступную для записи папку и закройте программы, блокирующие файлы.");
        } catch (IOException e) {
            printError("JCSM-105", "Ошибка файловой системы", e.getMessage(),
                    "Проверьте свободное место, доступ к папке установки и отсутствие блокировки файлов.");
        } catch (SecurityException e) {
            printError("JCSM-104", "Доступ запрещён", e.getMessage(),
                    "Разрешите запись в домашнюю папку пользователя или используйте учётную запись с нужными правами.");
        } catch (InvalidPathException e) {
            printError("JCSM-106", "Повреждённая конфигурация пути", e.getMessage(),
                    "Проверьте значение домашней папки пользователя и повторите запуск из обычного терминала.");
        }
    }

    private static void install() throws IOException, InstallFailure {
        Path source = Paths.get("compiler.java").toAbsolutePath().normalize();
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        Path userHome = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path installDirectory = userHome.resolve(INSTALL_DIRECTORY);

        showProgress(0, "Проверка комплекта установки");
        if (!Files.isRegularFile(source)) {
            throw new InstallFailure("JCSM-101", "Исходный файл не найден",
                    "Рядом с мастером установки отсутствует файл compiler.java.",
                    "Поместите файл compiler.java в текущую папку и запустите мастер из этой папки.");
        }
        if (javaCompiler == null) {
            throw new InstallFailure("JCSM-102", "Не найден компилятор JDK",
                    "Текущая Java-среда не содержит JavaCompiler; обычно это означает, что установлен только JRE.",
                    "Установите JDK и запустите мастер командой java installer из терминала с этим JDK.");
        }
        showProgress(15, "Проверка JDK и исходного файла");

        if (Files.exists(installDirectory) && !Files.isDirectory(installDirectory)) {
            throw new InstallFailure("JCSM-106", "Неверная папка установки",
                    "Путь установки уже занят файлом: " + installDirectory,
                    "Удалите или переименуйте этот файл либо освободите путь " + installDirectory + ".");
        }
        Files.createDirectories(installDirectory);
        showProgress(35, "Подготовка папки установки");

        Path installedSource = installDirectory.resolve("compiler.java");
        Files.copy(source, installedSource, StandardCopyOption.REPLACE_EXISTING);
        showProgress(55, "Копирование исходного кода");

        int compileResult = javaCompiler.run(null, null, null,
                "-d", installDirectory.toString(), installedSource.toString());
        if (compileResult != 0) {
            throw new InstallFailure("JCSM-103", "Не удалось собрать компилятор",
                    "JDK вернул код " + compileResult + "; исходный файл повреждён или несовместим с установленным JDK.",
                    "Проверьте compiler.java, установите актуальный JDK и повторите установку.");
        }
        showProgress(85, "Сборка классов компилятора");

        Path launcher = installDirectory.resolve("jcs-manager.cmd");
        String launcherContent = "@echo off\r\n"
                + "java -cp \"%~dp0.\" JavaCompilerScriptsManager.compiler %*\r\n";
        Files.write(launcher, launcherContent.getBytes(StandardCharsets.UTF_8));
        showProgress(100, "Установка завершена");

        System.out.println();
        System.out.println("Java Compiler Scripts Manager установлен.");
        System.out.println("Исходный код и классы: " + installDirectory);
        System.out.println("Команда запуска: \"" + launcher + "\"");
        System.out.println("Зависимости и интернет-соединение для установки не требуются: используется JDK.");
    }

    private static void showProgress(int percent, String stage) {
        int width = 30;
        int filled = percent * width / 100;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        bar.append("] ").append(percent).append("% - ").append(stage);
        System.out.print("\r" + bar);
        if (percent == 100) {
            System.out.println();
        }
    }

    private static void printError(String code, String title, String reason, String fix) {
        System.err.println();
        System.err.println("ОШИБКА " + code + ": " + title);
        System.err.println("Причина: " + reason);
        System.err.println("Как исправить?: " + fix);
        System.err.println("Сеть и серверы не используются при установке; если исходник был скачан, проверьте его целостность и скачайте заново при необходимости.");
    }

    private static final class InstallFailure extends Exception {
        private final String code;
        private final String title;
        private final String reason;
        private final String fix;

        private InstallFailure(String code, String title, String reason, String fix) {
            this.code = code;
            this.title = title;
            this.reason = reason;
            this.fix = fix;
        }
    }
}
