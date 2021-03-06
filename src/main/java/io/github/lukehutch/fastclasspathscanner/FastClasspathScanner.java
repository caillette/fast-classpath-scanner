/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.lukehutch.fastclasspathscanner;

import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take an
 * order of magnitude more time than parsing the classfile directly.)
 * 
 * This classpath scanner is able to scan directories and jar/zip files on the classpath to:
 * 
 * (1) find classes that subclass a given class or one of its subclasses;
 * 
 * (2) find classes that implement an interface or one of its subinterfaces;
 * 
 * (3) find interfaces that extend another given interface;
 * 
 * (4) find classes that have a given annotation;
 * 
 * (5) find classes that contain a specific static final field, returning the constant literal value used to initialize
 * the field in the classfile;
 * 
 * (6) find file paths (even for non-classfiles) anywhere on the classpath that match a given regexp;
 * 
 * (7) detect changes to the contents of the classpath after the initial scan;
 * 
 * (8) return a list of all directories and files on the classpath (i.e. all classpath elements) as a list of File
 * objects, with the list deduplicated and filtered to include only classpath directories and files that actually exist;
 * and
 * 
 * (9) return a list of the names of all classes and interfaces on the classpath (after whitelist and blacklist
 * filtering).
 * 
 * See the accompanying README.md file for complete documentation.
 */
public class FastClasspathScanner {

    /**
     * List of directory path prefixes to scan (produced from list of package prefixes passed into the constructor)
     */
    private final String[] whitelistedPathsToScan, blacklistedPathsToScan;

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since the
     * Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile itself is
     * considered.
     */
    private long lastModified = 0;

    /**
     * If this is set to true, then the timestamps of zipfile entries should be used to determine when files inside a
     * zipfile have changed; if set to false, then the timestamp of the zipfile itself is used. Itis recommended to
     * leave this set to false, since zipfile timestamps are less trustworthy than filesystem timestamps.
     */
    private static final boolean USE_ZIPFILE_ENTRY_MODIFICATION_TIMES = false;

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given regexp.
     */
    private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /**
     * A map from fully-qualified class name, to static field name, to a StaticFieldMatchProcessor to call when the
     * class name and static field name matches for a static field in a classfile.
     */
    private final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> //
    classNameToStaticFieldnameToMatchProcessor = new HashMap<>();

    /**
     * Classes encountered so far during a scan. If the same fully-qualified classname is encountered more than once,
     * the second and subsequent instances are ignored, because they are masked by the earlier occurrence in the
     * classpath.
     */
    private final HashSet<String> classesEncounteredSoFarDuringScan = new HashSet<>();

    /** The class and interface graph builder. */
    private final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder();

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Constructs a FastClasspathScanner instance.
     * 
     * @param packagesToScan
     *            the whitelist of package prefixes to scan, e.g. "com.xyz.widget", "com.xyz.gizmo". If no whitelisted
     *            packages are given (i.e. if the constructor is called with zero arguments), or a whitelisted package
     *            is "", then all packages on the classpath are whitelisted. If a package name is prefixed with "-",
     *            e.g. "-com.xyz.otherthing", then that package is blacklisted, rather than whitelisted. The final list
     *            of packages scanned is the set of whitelisted packages minus the set of blacklisted packages.
     */
    public FastClasspathScanner(final String... packagesToScan) {
        final HashSet<String> uniqueWhitelistedPathsToScan = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathsToScan = new HashSet<>();
        boolean scanAll = false;
        if (packagesToScan.length == 0) {
            scanAll = true;
        } else {
            for (final String packageToScan : packagesToScan) {
                if (packageToScan.isEmpty()) {
                    scanAll = true;
                    break;
                }
                String pkg = packageToScan.replace('.', '/') + "/";
                final boolean blacklisted = pkg.startsWith("-");
                if (blacklisted) {
                    pkg = pkg.substring(1);
                }
                (blacklisted ? uniqueBlacklistedPathsToScan : uniqueWhitelistedPathsToScan).add(pkg);
            }
        }
        uniqueWhitelistedPathsToScan.removeAll(uniqueBlacklistedPathsToScan);
        if (scanAll) {
            this.whitelistedPathsToScan = new String[] { "/" };
        } else {
            this.whitelistedPathsToScan = new String[uniqueWhitelistedPathsToScan.size()];
            int i = 0;
            for (final String path : uniqueWhitelistedPathsToScan) {
                this.whitelistedPathsToScan[i++] = path;
            }
        }
        this.blacklistedPathsToScan = new String[uniqueBlacklistedPathsToScan.size()];
        int i = 0;
        for (final String path : uniqueBlacklistedPathsToScan) {
            this.blacklistedPathsToScan[i++] = path;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException. */
    public <T> Class<? extends T> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> klass = (Class<? extends T>) Class.forName(className);
            return klass;
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new RuntimeException("Exception while loading or initializing class " + className, e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass. Will call the class loader on each matching class (using Class.forName()) before calling the
     * SubclassMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        if (superclass.isInterface()) {
            throw new IllegalArgumentException(superclass.getName() + " is an interface, not a regular class");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String subclass : classGraphBuilder.getNamesOfSubclassesOf(superclass.getName())) {
                    // Call classloader
                    final Class<? extends T> klass = loadClass(subclass);
                    // Process match
                    subclassMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfSubclassesOf(final Class<T> superclass) {
        if (superclass.isInterface()) {
            throw new IllegalArgumentException(superclass.getName() + " is an interface, not a regular class");
        }
        return classGraphBuilder.getNamesOfSubclassesOf(superclass.getName());
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclassName
     *            The name of the superclass to match (i.e. the name of the class that subclasses need to extend).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName) {
        return classGraphBuilder.getNamesOfSubclassesOf(superclassName);
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be called
     * after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfSuperclassesOf(final Class<T> subclass) {
        if (subclass.isInterface()) {
            throw new IllegalArgumentException(subclass.getName() + " is an interface, not a regular class");
        }
        return classGraphBuilder.getNamesOfSuperclassesOf(subclass.getName());
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be called
     * after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName) {
        return classGraphBuilder.getNamesOfSuperclassesOf(subclassName);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on the
     * classpath. Will call the class loader on each matching interface (using Class.forName()) before calling the
     * SubinterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superInterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        if (!superInterface.isInterface()) {
            throw new IllegalArgumentException(superInterface.getName() + " is not an interface");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String subInterface : classGraphBuilder.getNamesOfSubinterfacesOf(superInterface.getName())) {
                    // Call classloader
                    final Class<? extends T> klass = loadClass(subInterface);
                    // Process match
                    subinterfaceMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their names.
     * 
     * @param superInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public <T> List<String> getNamesOfSubinterfacesOf(final Class<T> superInterface) {
        if (!superInterface.isInterface()) {
            throw new IllegalArgumentException(superInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfSubinterfacesOf(superInterface.getName());
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their names.
     * 
     * @param superInterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String superInterfaceName) {
        return classGraphBuilder.getNamesOfSubinterfacesOf(superInterfaceName);
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param subInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public <T> List<String> getNamesOfSuperinterfacesOf(final Class<T> subInterface) {
        if (!subInterface.isInterface()) {
            throw new IllegalArgumentException(subInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterface.getName());
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * 
     * @param subInterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subInterfaceName) {
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterfaceName);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified interface or
     * a subinterface, or whose superclasses implement the specified interface or a sub-interface. Will call the class
     * loader on each matching interface (using Class.forName()) before calling the InterfaceMatchProcessor. Does not
     * call the classloader on non-matching classes or interfaces.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        if (!implementedInterface.isInterface()) {
            throw new IllegalArgumentException(implementedInterface.getName() + " is not an interface");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                // For all classes implementing the given interface
                for (final String implClass : classGraphBuilder.getNamesOfClassesImplementing(implementedInterface
                        .getName())) {
                    // Call classloader
                    final Class<? extends T> klass = loadClass(implClass);
                    // Process match
                    interfaceMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or whose
     * superclasses implement the specified interface or a sub-interface. Should be called after scan(), and returns
     * matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan().
     * Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement to match.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfClassesImplementing(final Class<T> implementedInterface) {
        if (!implementedInterface.isInterface()) {
            throw new IllegalArgumentException(implementedInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfClassesImplementing(implementedInterface.getName());
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or whose
     * superclasses implement the specified interface or a sub-interface. Should be called after scan(), and returns
     * matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan().
     * Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceName
     *            The name of the interface that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementing(final String implementedInterfaceName) {
        return classGraphBuilder.getNamesOfClassesImplementing(implementedInterfaceName);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor if classes are found on the classpath that have the specified annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                // For all classes with the given annotation
                for (final String classWithAnnotation : classGraphBuilder.getNamesOfClassesWithAnnotation(annotation
                        .getName())) {
                    // Call classloader
                    final Class<?> klass = loadClass(classWithAnnotation);
                    // Process match
                    classAnnotationMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after scan(),
     * and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotation
     *            The class annotation.
     * @return A list of the names of classes with the class annotation, or the empty list if none.
     */
    public <T> List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotation.getName());
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after scan(),
     * and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotationName
     *            The name of the class annotation.
     * @return A list of the names of classes that have the named annotation, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotationName);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static final
     * fields that match one of a set of fully-qualified field names, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection. This
     * allows you to detect changes to the classpath and then run another scan that picks up the new values of selected
     * static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values that
     * are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final HashSet<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                HashMap<String, StaticFinalFieldMatchProcessor> fieldNameToMatchProcessor = //
                classNameToStaticFieldnameToMatchProcessor.get(className);
                if (fieldNameToMatchProcessor == null) {
                    classNameToStaticFieldnameToMatchProcessor.put(className,
                            fieldNameToMatchProcessor = new HashMap<>());
                }
                fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
            }
        }
        return this;
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static final
     * fields that match one of a list of fully-qualified field names, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * (The parameters are reversed in this method, relative to the other methods of this class, because the varargs
     * parameter must come last.)
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection. This
     * allows you to detect changes to the classpath and then run another scan that picks up the new values of selected
     * static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values that
     * are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor,
            final String... fullyQualifiedStaticFinalFieldNames) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedFieldName);
        }
        return matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param filenameMatchPattern
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String filenameMatchPattern,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(Pattern.compile(filenameMatchPattern), fileMatchProcessor));
        return this;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** An interface used for testing if a file path matches a specified pattern. */
    private static class FilePathMatcher {
        Pattern pattern;
        FileMatchProcessor fileMatchProcessor;

        public FilePathMatcher(final Pattern pattern, final FileMatchProcessor fileMatchProcessor) {
            this.pattern = pattern;
            this.fileMatchProcessor = fileMatchProcessor;
        }
    }

    /** An interface used for testing if a class matches specified criteria. */
    private static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Returns the names of all classes and interfaces processed during the scan, i.e. all classes reachable after
     * taking into account the package whitelist and blacklist criteria.
     */
    public <T> Set<String> getNamesOfAllClasses() {
        return classGraphBuilder.getNamesOfAllClasses();
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Read annotation entry from classfile.
     */
    private String readAnnotation(final DataInputStream inp, final Object[] constantPool) throws IOException {
        final String annotationFieldDescriptor = readRefdString(inp, constantPool);
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom/xyz/Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1)
                    .replace('/', '.');
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        final int numElementValuePairs = inp.readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            inp.skipBytes(2); // element_name_index
            readAnnotationElementValue(inp, constantPool);
        }
        return annotationClassName;
    }

    /**
     * Read annotation element value from classfile.
     */
    private void readAnnotationElementValue(final DataInputStream inp, final Object[] constantPool) throws IOException {
        final int tag = inp.readUnsignedByte();
        switch (tag) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
        case 's':
            // const_value_index
            inp.skipBytes(2);
            break;
        case 'e':
            // enum_const_value
            inp.skipBytes(4);
            break;
        case 'c':
            // class_info_index
            inp.skipBytes(2);
            break;
        case '@':
            // Complex (nested) annotation
            readAnnotation(inp, constantPool);
            break;
        case '[':
            // array_value
            final int count = inp.readUnsignedShort();
            for (int l = 0; l < count; ++l) {
                // Nested annotation element value
                readAnnotationElementValue(inp, constantPool);
            }
            break;
        default:
            // System.err.println("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
            break;
        }
    }

    /**
     * Read as usigned short constant pool reference, then look up the string in the constant pool.
     */
    private static String readRefdString(final DataInputStream inp, final Object[] constantPool) throws IOException {
        return (String) constantPool[inp.readUnsignedShort()];
    }

    /**
     * Directly examine contents of classfile binary header.
     */
    private void readClassInfoFromClassfileHeader(final InputStream inputStream) throws IOException {
        final DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 1024));

        // Magic
        if (inp.readInt() != 0xCAFEBABE) {
            // Not classfile
            return;
        }

        // Minor version
        inp.readUnsignedShort();
        // Major version
        inp.readUnsignedShort();

        // Constant pool count (1-indexed, zeroth entry not used)
        final int cpCount = inp.readUnsignedShort();
        // Constant pool
        final Object[] constantPool = new Object[cpCount];
        final int[] indirectStringRef = new int[cpCount];
        Arrays.fill(indirectStringRef, -1);
        for (int i = 1; i < cpCount; ++i) {
            final int tag = inp.readUnsignedByte();
            switch (tag) {
            case 1: // Modified UTF8
                constantPool[i] = inp.readUTF();
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
                constantPool[i] = inp.readInt();
                break;
            case 4: // float
                constantPool[i] = inp.readFloat();
                break;
            case 5: // long
                constantPool[i] = inp.readLong();
                i++; // double slot
                break;
            case 6: // double
                constantPool[i] = inp.readDouble();
                i++; // double slot
                break;
            case 7: // Class
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRef[i] = inp.readUnsignedShort();
                break;
            case 9: // field ref
            case 10: // method ref
            case 11: // interface ref
            case 12: // name and type
                inp.skipBytes(4); // two shorts
                break;
            case 15: // method handle
                inp.skipBytes(3);
                break;
            case 16: // method type
                inp.skipBytes(2);
                break;
            case 18: // invoke dynamic
                inp.skipBytes(4);
                break;
            default:
                // System.err.println("Unkown tag value for constant pool entry: " + tag);
                break;
            }
        }
        // Resolve indirection of string references now that all the strings have been read
        // (allows forward references to strings before they have been encountered)
        for (int i = 1; i < cpCount; i++) {
            if (indirectStringRef[i] >= 0) {
                constantPool[i] = constantPool[indirectStringRef[i]];
            }
        }

        // Access flags
        final int flags = inp.readUnsignedShort();
        final boolean isInterface = (flags & 0x0200) != 0;

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String className = readRefdString(inp, constantPool).replace('/', '.');
        if (className.equals("java.lang.Object")) {
            // java.lang.Object doesn't have a superclass to be linked to, can simply return
            return;
        }

        // Determine if this fully-qualified class name has already been encountered during this scan
        if (!classesEncounteredSoFarDuringScan.add(className)) {
            // If so, skip this classfile, because the earlier class with the same name as this one
            // occurred earlier on the classpath, so it masks this one.
            return;
        }

        // Superclass name, with slashes replaced with dots
        final String superclassName = readRefdString(inp, constantPool).replace('/', '.');

        // Look up static field name match processors given class name 
        final HashMap<String, StaticFinalFieldMatchProcessor> staticFieldnameToMatchProcessor = //
        classNameToStaticFieldnameToMatchProcessor.get(className);

        // Interfaces
        final int interfaceCount = inp.readUnsignedShort();
        final ArrayList<String> interfaces = interfaceCount > 0 ? new ArrayList<String>() : null;
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(readRefdString(inp, constantPool).replace('/', '.'));
        }

        // Fields
        final int fieldCount = inp.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            final int accessFlags = inp.readUnsignedShort();
            // See http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final boolean isStaticFinal = (accessFlags & 0x0018) == 0x0018;
            final String fieldName = readRefdString(inp, constantPool);
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor = staticFieldnameToMatchProcessor != null //
            ? staticFieldnameToMatchProcessor.get(fieldName)
                    : null;
            final String descriptor = readRefdString(inp, constantPool);
            final int attributesCount = inp.readUnsignedShort();
            if (!isStaticFinal && staticFinalFieldMatchProcessor != null) {
                // Requested to match a field that is not static or not final
                System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                        + ": cannot match requested field " + className + "." + fieldName
                        + " because it is either not static or not final");
            } else if (!isStaticFinal || staticFinalFieldMatchProcessor == null) {
                // Not matching this static final field, just skip field attributes rather than parsing them
                for (int j = 0; j < attributesCount; j++) {
                    inp.skipBytes(2); // attribute_name_index
                    final int attributeLength = inp.readInt();
                    inp.skipBytes(attributeLength);
                }
            } else {
                // Look for static final fields that match one of the requested names,
                // and that are initialized with a constant value
                boolean foundConstantValue = false;
                for (int j = 0; j < attributesCount; j++) {
                    final String attributeName = readRefdString(inp, constantPool);
                    final int attributeLength = inp.readInt();
                    if (attributeName.equals("ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        Object constValue = constantPool[inp.readUnsignedShort()];
                        // byte, char, short and boolean constants are all stored as 4-byte int
                        // values -- coerce and wrap in the proper wrapper class with autoboxing
                        switch (descriptor) {
                        case "B":
                            // Convert byte store in Integer to Byte
                            constValue = ((Integer) constValue).byteValue();
                            break;
                        case "C":
                            // Convert char stored in Integer to Character
                            constValue = (char) ((Integer) constValue).intValue();
                            break;
                        case "S":
                            // Convert char stored in Integer to Short
                            constValue = ((Integer) constValue).shortValue();
                            break;
                        case "Z":
                            // Convert char stored in Integer to Boolean
                            constValue = ((Integer) constValue).intValue() != 0;
                            break;
                        case "I":
                        case "J":
                        case "F":
                        case "D":
                        case "Ljava.lang.String;":
                            // Field is int, long, float, double or String => object is already in correct
                            // wrapper type (Integer, Long, Float, Double or String), nothing to do
                            break;
                        default:
                            // Should never happen:
                            // constant values can only be stored as an int, long, float, double or String
                            break;
                        }
                        // Call static final field match processor
                        staticFinalFieldMatchProcessor.processMatch(className, fieldName, constValue);
                        foundConstantValue = true;
                    } else {
                        inp.skipBytes(attributeLength);
                    }
                    if (!foundConstantValue) {
                        System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                                + ": Requested static final field " + className + "." + fieldName
                                + "is not initialized with a constant literal value, so there is no "
                                + "initializer value in the constant pool of the classfile");
                    }
                }
            }
        }

        // Methods
        final int methodCount = inp.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            inp.skipBytes(6); // access_flags, name_index, descriptor_index
            final int attributesCount = inp.readUnsignedShort();
            for (int j = 0; j < attributesCount; j++) {
                inp.skipBytes(2); // attribute_name_index
                final int attributeLength = inp.readInt();
                inp.skipBytes(attributeLength);
            }
        }

        // Attributes (including class annotations)
        HashSet<String> annotations = null;
        final int attributesCount = inp.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final String attributeName = readRefdString(inp, constantPool);
            final int attributeLength = inp.readInt();
            if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                final int annotationCount = inp.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    final String annotationName = readAnnotation(inp, constantPool);
                    if (annotations == null) {
                        annotations = new HashSet<>();
                    }
                    annotations.add(annotationName);
                }
            } else {
                inp.skipBytes(attributeLength);
            }
        }

        if (isInterface) {
            classGraphBuilder
                    .linkToSuperinterfaces(/* interfaceName = */className, /* superInterfaces = */interfaces);

        } else {
            classGraphBuilder.linkToSuperclassAndInterfaces(className, superclassName, interfaces, annotations);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Scan a file.
     */
    private void scanFile(final File file, final String absolutePath, final String relativePath,
            final boolean scanTimestampsOnly) throws IOException {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            if (relativePath.endsWith(".class")) {
                // Found a classfile
                try (InputStream inputStream = new FileInputStream(file)) {
                    // Inspect header of classfile
                    readClassInfoFromClassfileHeader(inputStream);
                }
            } else {
                // For non-classfiles, match file paths against path patterns
                for (final FilePathMatcher fileMatcher : filePathMatchers) {
                    if (fileMatcher.pattern.matcher(relativePath).matches()) {
                        // If there's a match, open the file as a stream and call the match processor
                        try (InputStream inputStream = new FileInputStream(file)) {
                            fileMatcher.fileMatchProcessor.processMatch(absolutePath, relativePath, inputStream);
                        }
                    }
                }
            }
        }
    }

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File dir, final int ignorePrefixLen, boolean inWhitelistedPath,
            final boolean scanTimestampsOnly) throws IOException {
        String relativePath = (ignorePrefixLen > dir.getPath().length() ? "" : dir.getPath().substring(ignorePrefixLen))
                + "/";
        if (File.separatorChar != '/') {
            // Fix scanning on Windows
            relativePath = relativePath.replace(File.separatorChar, '/');
        }
        for (final String blacklistedPath : blacklistedPathsToScan) {
            if (relativePath.equals(blacklistedPath)) {
                // Reached a blacklisted path -- stop scanning files and dirs
                return;
            }
        }
        boolean keepRecursing = false;
        if (!inWhitelistedPath) {
            // If not yet within a subtree of a whitelisted path, see if the current path is at least a prefix of
            // a whitelisted path, and if so, keep recursing until we hit a whitelisted path.
            for (final String whitelistedPath : whitelistedPathsToScan) {
                if (relativePath.equals(whitelistedPath)) {
                    // Reached a whitelisted path -- can start scanning directories and files from this point
                    inWhitelistedPath = true;
                    break;
                } else if (whitelistedPath.startsWith(relativePath) || relativePath.equals("/")) {
                    // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
                    // in case we can reach a whitelisted path.
                    keepRecursing = true;
                }
            }
        }
        if (keepRecursing || inWhitelistedPath) {
            lastModified = Math.max(lastModified, dir.lastModified());
            final File[] subFiles = dir.listFiles();
            for (final File subFile : subFiles) {
                if (subFile.isDirectory()) {
                    // Recurse into subdirectory
                    scanDir(subFile, ignorePrefixLen, inWhitelistedPath, scanTimestampsOnly);
                } else if (inWhitelistedPath && subFile.isFile()) {
                    // Scan file
                    scanFile(subFile, dir.getPath() + "/" + subFile.getName(), relativePath + subFile.getName(),
                            scanTimestampsOnly);
                }
            }
        }
    }

    /**
     * Scan a zipfile for matching file path patterns. (Does not recurse into zipfiles within zipfiles.)
     */
    private void scanZipfile(final String zipfilePath, final ZipFile zipFile, final long zipFileLastModified,
            final boolean scanTimestampsOnly) throws IOException {
        boolean timestampWarning = false;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Scan for matching filenames
            final ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                // Only process file entries (zipfile indices contain both directory entries and
                // separate file entries for files within each directory, in lexicographic order)
                final String path = entry.getName();
                boolean scanFile = false;
                for (final String whitelistedPath : whitelistedPathsToScan) {
                    if (path.startsWith(whitelistedPath) //
                            || whitelistedPath.equals("/")) {
                        // File path has a whitelisted path as a prefix -- can scan file
                        scanFile = true;
                        break;
                    }
                }
                for (final String blacklistedPath : blacklistedPathsToScan) {
                    if (path.startsWith(blacklistedPath)) {
                        // File path has a blacklisted path as a prefix -- don't scan it
                        scanFile = false;
                        break;
                    }
                }
                if (scanFile) {
                    // If USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is true, use zipfile entry timestamps,
                    // otherwise use the modification time of the zipfile itself. Using zipfile entry
                    // timestamps assumes that the timestamp on zipfile entries was properly added, and
                    // that the clock of the machine adding the zipfile entries is in sync with the 
                    // clock used to timestamp regular file and directory entries in the current
                    // classpath. USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is set to false by default,
                    // as zipfile entry timestamps are less trustworthy than filesystem timestamps.
                    final long entryTime = USE_ZIPFILE_ENTRY_MODIFICATION_TIMES //
                    ? entry.getTime()
                            : zipFileLastModified;
                    lastModified = Math.max(lastModified, entryTime);
                    if (entryTime > System.currentTimeMillis() && !timestampWarning) {
                        final String msg = zipfilePath + " contains modification timestamps after the current time";
                        // Log.warning(msg);
                        System.err.println(msg);
                        // Only warn once
                        timestampWarning = true;
                    }
                    if (!scanTimestampsOnly) {
                        if (path.endsWith(".class")) {
                            // Found a classfile, open it as a stream and inspect header
                            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                                readClassInfoFromClassfileHeader(inputStream);
                            }
                        } else {
                            // For non-classfiles, match file paths against path patterns
                            for (final FilePathMatcher fileMatcher : filePathMatchers) {
                                if (fileMatcher.pattern.matcher(path).matches()) {
                                    // There's a match -- open the file as a stream and
                                    // call the match processor
                                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                                        fileMatcher.fileMatchProcessor.processMatch(path, path, inputStream);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Get a list of unique elements on the classpath (directories and files) as File objects, preserving order.
     * Classpath elements that do not exist are not included in the list.
     */
    public static ArrayList<File> getUniqueClasspathElements() {
        final String[] pathElements = System.getProperty("java.class.path").split(File.pathSeparator);
        final HashSet<String> pathElementsSet = new HashSet<>();
        final ArrayList<File> pathFiles = new ArrayList<>();
        for (final String pathElement : pathElements) {
            if (pathElementsSet.add(pathElement)) {
                final File file = new File(pathElement);
                if (file.exists()) {
                    pathFiles.add(file);
                }
            }
        }
        return pathFiles;
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "get" methods (e.g. getSubclassesOf()).
     */
    private FastClasspathScanner scan(final boolean scanTimestampsOnly) {
        // long scanStart = System.currentTimeMillis();

        classesEncounteredSoFarDuringScan.clear();
        if (!scanTimestampsOnly) {
            classGraphBuilder.reset();
        }

        try {
            // Iterate through path elements and recursively scan within each directory and zipfile
            for (final File pathElt : getUniqueClasspathElements()) {
                final String path = pathElt.getPath();
                if (pathElt.isDirectory()) {
                    // Scan within dir path element
                    scanDir(pathElt, path.length() + 1, false, scanTimestampsOnly);
                } else if (pathElt.isFile()) {
                    final String pathLower = path.toLowerCase();
                    if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
                        // Scan within jar/zipfile path element
                        scanZipfile(path, new ZipFile(pathElt), pathElt.lastModified(), scanTimestampsOnly);
                    } else {
                        // File listed directly on classpath
                        scanFile(pathElt, path, pathElt.getName(), scanTimestampsOnly);

                        for (final FilePathMatcher fileMatcher : filePathMatchers) {
                            if (fileMatcher.pattern.matcher(path).matches()) {
                                // If there's a match, open the file as a stream and call the
                                // match processor
                                try (InputStream inputStream = new FileInputStream(pathElt)) {
                                    fileMatcher.fileMatchProcessor.processMatch(path, pathElt.getName(), inputStream);
                                }
                            }
                        }
                    }
                } else {
                    // Log.info("Skipping non-file/non-dir on classpath: " + file.getCanonicalPath());
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

        if (!scanTimestampsOnly) {
            // Finalize class and interface DAGs
            classGraphBuilder.finalizeNodes();
            // Look for class and interface matches
            for (final ClassMatcher classMatcher : classMatchers) {
                classMatcher.lookForMatches();
            }
        }
        // Log.info("Classpath " + (scanTimestampsOnly ? "timestamp " : "") + "scanning took: "
        //      + (System.currentTimeMillis() - scanStart) + " ms");
        return this;
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "get" methods (e.g. getSubclassesOf()).
     */
    public FastClasspathScanner scan() {
        return scan(/* scanTimestampsOnly = */false);
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers classpath
     * prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long oldLastModified = this.lastModified;
        if (oldLastModified == 0) {
            return true;
        } else {
            scan(/* scanTimestampsOnly = */true);
            final long newLastModified = this.lastModified;
            return newLastModified > oldLastModified;
        }
    }
}
