package com.github.davidmoten.bean;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.maven.plugin.logging.Log;
import org.reflections.Reflections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.davidmoten.bean.annotation.ImmutableBean;
import com.github.davidmoten.bean.annotation.NonNull;

public final class BeanGenerator {

    public static void scanAndGenerate(File sourceDirectory, File outputSourceDirectory) {
        scanAndGenerate(sourceDirectory, sourceDirectory, "", outputSourceDirectory);
    }

    private static void scanAndGenerate(File sourceDirectory, File directory, String pkg, File outputSourceDirectory) {
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                scanAndGenerate(sourceDirectory, file, pkg + "." + file.getName(), outputSourceDirectory);
            } else if (file.getName().endsWith(".java")) {
                String className = pkg + ".bean." + file.getName().substring(0, file.getName().lastIndexOf("."));
                try {
                    Class<?> cls = Class.forName(className);
                    if (Arrays //
                            .stream(cls.getAnnotations()) //
                            .anyMatch(x -> x instanceof ImmutableBean)) {
                        generate(cls, outputSourceDirectory);
                    }
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void scanAndGenerate(String packageName, File outputSourceDirectory) {
        System.out.println("scanning "+ packageName);
        Reflections r = new Reflections(packageName);
        for (Class<?> cls: r.getTypesAnnotatedWith(ImmutableBean.class)) {
            System.out.println(cls);
            generate(cls, outputSourceDirectory);
        }
    }

    /**
     * @param cls
     * @param generatedSource
     */
    public static void generate(Class<?> cls, File generatedSource) {
        String pkg = cls.getPackage().getName();
        String pkg2 = pkg + ".bean";
        String className = cls.getSimpleName();
        String path = pkg2.replace(".", File.separator);
        File directory = new File(generatedSource, path);
        directory.mkdirs();
        File file = new File(directory, className + ".java");
        Map<Class<?>, String> imports = new HashMap<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes);
        out.format(
                "/////////////////////////////////////////////////////\n" + //
                        "// WARNING - Generated data class! \n" //
                        + "/////////////////////////////////////////////////////\n", //
                resolve(imports, ImmutableBean.class));
        out.println();
        out.format("@%s\n", resolve(imports, ImmutableBean.class));
        out.format("public class %s {\n", className);
        out.println();

        List<Field> fields = Arrays //
                .stream(cls.getDeclaredFields()) //
                .filter(c -> !c.getName().startsWith("$")) //
                .collect(Collectors.toList());

        for (Field field : fields) {
            Class<?> type = field.getType();
            String name = field.getName();
            for (Annotation a : field.getAnnotations()) {
                System.out.println(a);
                if (a.annotationType().equals(NonNull.class)) {
                    out.format("    @%s\n", resolve(imports, NonNull.class));
                }
            }
            out.format("    private %s %s;\n", resolve(imports, type), name);
        }

        // constructor params
        StringBuffer params = new StringBuffer();
        for (Field field : fields) {
            Class<?> type = field.getType();
            String name = field.getName();
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(String.format("\n          @%s(\"%s\") ", resolve(imports, JsonProperty.class), name));
            params.append(resolve(imports, type) + " " + name);
        }

        // constructor
        out.println();
        out.format("    @%s\n", resolve(imports, JsonCreator.class));
        out.format("    public %s(%s) {\n", cls.getSimpleName(), params);

        // TODO make checkForNull configurable
        boolean checkForNull = false;
        if (checkForNull) {
            for (Field field : fields) {
                String name = field.getName();
                Class<?> type = field.getType();
                if ((!type.isPrimitive() || type.isArray()) && isNonNull(type.getAnnotations())) {
                    out.format("        if (%s == null) {\n", name);
                    out.format("            throw new %s(\"'%s' parameter cannot be null\");\n",
                            resolve(imports, NullPointerException.class), name);
                    out.format("        }\n");
                }
            }
        }
        for (Field field : fields) {
            String name = field.getName();
            out.format("        this.%s = %s;\n", name, name);
        }
        out.format("    }\n");

        // build comma delimited params
        StringBuffer flds = new StringBuffer();
        for (Field field : fields) {
            String name = field.getName();
            if (flds.length() > 0) {
                flds.append(", ");
            }
            flds.append(name);
        }

        // static factory
        // build create method params
        StringBuffer params2 = new StringBuffer();
        for (Field field : fields) {
            Class<?> type = field.getType();
            String name = field.getName();
            if (params2.length() > 0) {
                params2.append(", ");
            }
            params2.append(String.format("%s %s", resolve(imports, type), name));
        }
        out.println();
        out.format("    public static %s create(%s) {\n", className, params2);
        out.format("        return new %s(%s);\n", className, flds);
        out.format("    }");
        out.println();

        // getters
        for (Field field : fields) {
            Class<?> type = field.getType();
            String name = field.getName();
            out.println();
            out.format("    public %s %s() {\n", resolve(imports, type), name);
            out.format("        return %s;\n", name);
            out.format("    }\n");
        }

        // with fields
        for (Field field : fields) {
            Class<?> type = field.getType();
            String name = field.getName();
            out.println();
            out.format("    public %s with%s(%s %s) {\n", className, capFirst(name), resolve(imports, type), name);
            out.format("        return new %s(%s);\n", className, flds);
            out.format("    }\n");
        }

        // hashCode
        out.println();
        out.format("    @%s\n", resolve(imports, Override.class));
        out.format("    public int hashCode() {\n");
        out.format("        return %s.hash(%s);\n", resolve(imports, Objects.class), flds);
        out.format("    }\n");

        // equals
        out.println();
        out.format("    @%s\n", resolve(imports, Override.class));
        out.format("    public boolean equals(Object o) {\n");
        out.format("        return %s.equals(this, o);\n", resolve(imports, Objects.class));
        out.format("    }\n");

        out.println("}\n");
        out.close();

        // package
        StringBuffer w = new StringBuffer();
        w.append("package " + pkg2 + ";\n");
        w.append("\n");

        // imports
        for (Entry<Class<?>, String> entry : imports //
                .entrySet() //
                .stream() //
                .sorted((a, b) -> a.getKey().getName().compareTo(b.getKey().getName())) //
                .collect(Collectors.toList())) {
            Class<?> c = entry.getKey();
            if (!c.isPrimitive() && !c.getName().startsWith("java.lang.")) {
                w.append("import " + c.getName() + ";\n");
            }
        }
        w.append("\n");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(w.toString().getBytes(StandardCharsets.UTF_8));
            fos.write(bytes.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isNonNull(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().equals(NonNull.class)) {
                return true;
            }
        }
        return false;
    }

    private static String capFirst(String name) {
        if (name.length() <= 1) {
            return name.toUpperCase();
        } else {
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }
    }

    public static String resolve(Map<Class<?>, String> map, Class<?> cls) {
        Class<?> c;
        if (cls.isArray()) {
            c = cls.getComponentType();
        } else {
            c = cls;
        }
        final String name;
        if (map.containsKey(c)) {
            name = map.get(c);
        } else {
            if (map.values().contains(c.getSimpleName())) {
                map.put(c, c.getName());
                name = c.getName();
            } else {
                map.put(c, c.getSimpleName());
                name = c.getSimpleName();
            }
        }
        if (cls.isArray()) {
            return name + "[]";
        } else {
            return name;
        }
    }

}
