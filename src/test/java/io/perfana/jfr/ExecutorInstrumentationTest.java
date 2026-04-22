/*
 * Copyright (C) 2023 Peter Paul Bakker - Perfana
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.perfana.jfr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutorInstrumentationTest implements Opcodes {

    @AfterEach
    void clearRegistry() {
        DiscoveredExecutorRegistry.clear();
    }

    @Test
    void transformsExecutorsFactoryCallsIntoRegisteredExecutors() throws Exception {
        String internalName = "com/example/ExecutorFactory";
        byte[] original = generateClassCallingExecutorsFactory(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method create = cls.getDeclaredMethod("create");
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        java.util.concurrent.ExecutorService executor =
                (java.util.concurrent.ExecutorService) create.invoke(instance);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
            monitor.sample();
            assertEquals(1, captured.size());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void transformsThreadPoolExecutorConstructorWithNestedQueueAllocation() throws Exception {
        String internalName = "com/example/ThreadPoolExecutorFactory";
        byte[] original = generateClassCallingThreadPoolExecutorConstructor(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method create = cls.getDeclaredMethod("create");
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        ThreadPoolExecutor executor = (ThreadPoolExecutor) create.invoke(instance);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
            monitor.sample();
            assertEquals(1, captured.size());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void leavesSubclassSuperConstructorCallsUntouched() throws Exception {
        String internalName = "com/example/ThreadPoolExecutorSubclass";
        byte[] original = generateThreadPoolExecutorSubclass(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) cls.getDeclaredConstructor().newInstance();
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            assertEquals("com.example.ThreadPoolExecutorSubclass", executor.getClass().getName());
            try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
                monitor.sample();
                assertEquals(1, captured.size());
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void registersThreadPoolExecutorCreatedInsideConstructor() throws Exception {
        String internalName = "com/example/ConstructorExecutorHolder";
        byte[] original = generateClassCreatingThreadPoolExecutorInConstructor(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method getter = cls.getDeclaredMethod("getExecutor");
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        ThreadPoolExecutor executor = (ThreadPoolExecutor) getter.invoke(instance);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
            monitor.sample();
            assertEquals(1, captured.size());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void registersScheduledThreadPoolExecutorCreatedInsideConstructor() throws Exception {
        String internalName = "com/example/ScheduledConstructorExecutorHolder";
        byte[] original = generateClassCreatingScheduledThreadPoolExecutorInConstructor(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method getter = cls.getDeclaredMethod("getExecutor");
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) getter.invoke(instance);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
            monitor.sample();
            assertEquals(1, captured.size());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void registersTomcatThreadPoolExecutorConstructedInApplicationCode() throws Exception {
        String internalName = "com/example/TomcatThreadPoolExecutorFactory";
        byte[] original = generateClassCallingTomcatThreadPoolExecutorConstructor(internalName);
        ClassFileTransformer transformer = ExecutorInstrumentation.createTransformer();
        byte[] transformed = transformer.transform(null, internalName, null, null, original);

        Class<?> cls = new TestClassLoader().define(internalName.replace('/', '.'), transformed == null ? original : transformed);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method create = cls.getDeclaredMethod("create");
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) create.invoke(instance);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, java.time.Duration.ofSeconds(1), scheduler)) {
            monitor.sample();
            assertEquals(1, captured.size());
            assertEquals("org.apache.tomcat.util.threads.ThreadPoolExecutor", captured.get(0).tags().get("executorClass"));
        } finally {
            executor.shutdown();
        }
    }

    private static byte[] generateClassCallingExecutorsFactory(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "create", "()Ljava/util/concurrent/ExecutorService;", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC,
                "java/util/concurrent/Executors",
                "newFixedThreadPool",
                "(I)Ljava/util/concurrent/ExecutorService;",
                false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingThreadPoolExecutorConstructor(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "create", "()Ljava/util/concurrent/ThreadPoolExecutor;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/util/concurrent/ThreadPoolExecutor");
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(LCONST_0);
        mv.visitFieldInsn(GETSTATIC,
                "java/util/concurrent/TimeUnit",
                "MILLISECONDS",
                "Ljava/util/concurrent/TimeUnit;");
        mv.visitTypeInsn(NEW, "java/util/concurrent/LinkedBlockingQueue");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/LinkedBlockingQueue", "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESPECIAL,
                "java/util/concurrent/ThreadPoolExecutor",
                "<init>",
                "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;)V",
                false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(8, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateThreadPoolExecutorSubclass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/util/concurrent/ThreadPoolExecutor", null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitInsn(ICONST_1);
        ctor.visitInsn(ICONST_1);
        ctor.visitInsn(LCONST_0);
        ctor.visitFieldInsn(GETSTATIC,
                "java/util/concurrent/TimeUnit",
                "MILLISECONDS",
                "Ljava/util/concurrent/TimeUnit;");
        ctor.visitTypeInsn(NEW, "java/util/concurrent/LinkedBlockingQueue");
        ctor.visitInsn(DUP);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/LinkedBlockingQueue", "<init>", "()V", false);
        ctor.visitTypeInsn(CHECKCAST, "java/util/concurrent/BlockingQueue");
        ctor.visitMethodInsn(INVOKESPECIAL,
                "java/util/concurrent/ThreadPoolExecutor",
                "<init>",
                "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;)V",
                false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(8, 1);
        ctor.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCreatingThreadPoolExecutorInConstructor(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "executor", "Ljava/util/concurrent/ThreadPoolExecutor;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitTypeInsn(NEW, "java/util/concurrent/ThreadPoolExecutor");
        ctor.visitInsn(DUP);
        ctor.visitInsn(ICONST_1);
        ctor.visitInsn(ICONST_1);
        ctor.visitInsn(LCONST_0);
        ctor.visitFieldInsn(GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS", "Ljava/util/concurrent/TimeUnit;");
        ctor.visitTypeInsn(NEW, "java/util/concurrent/LinkedBlockingQueue");
        ctor.visitInsn(DUP);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/LinkedBlockingQueue", "<init>", "()V", false);
        ctor.visitMethodInsn(INVOKESPECIAL,
                "java/util/concurrent/ThreadPoolExecutor",
                "<init>",
                "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;)V",
                false);
        ctor.visitFieldInsn(PUTFIELD, internalName, "executor", "Ljava/util/concurrent/ThreadPoolExecutor;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(9, 1);
        ctor.visitEnd();

        MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, "getExecutor", "()Ljava/util/concurrent/ThreadPoolExecutor;", null, null);
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, internalName, "executor", "Ljava/util/concurrent/ThreadPoolExecutor;");
        getter.visitInsn(ARETURN);
        getter.visitMaxs(1, 1);
        getter.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCreatingScheduledThreadPoolExecutorInConstructor(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "executor", "Ljava/util/concurrent/ScheduledThreadPoolExecutor;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitTypeInsn(NEW, "java/util/concurrent/ScheduledThreadPoolExecutor");
        ctor.visitInsn(DUP);
        ctor.visitInsn(ICONST_1);
        ctor.visitMethodInsn(INVOKESPECIAL,
                "java/util/concurrent/ScheduledThreadPoolExecutor",
                "<init>",
                "(I)V",
                false);
        ctor.visitFieldInsn(PUTFIELD, internalName, "executor", "Ljava/util/concurrent/ScheduledThreadPoolExecutor;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(4, 1);
        ctor.visitEnd();

        MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, "getExecutor", "()Ljava/util/concurrent/ScheduledThreadPoolExecutor;", null, null);
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, internalName, "executor", "Ljava/util/concurrent/ScheduledThreadPoolExecutor;");
        getter.visitInsn(ARETURN);
        getter.visitMaxs(1, 1);
        getter.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateClassCallingTomcatThreadPoolExecutorConstructor(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "create", "()Ljava/util/concurrent/ExecutorService;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "org/apache/tomcat/util/threads/ThreadPoolExecutor");
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(LCONST_0);
        mv.visitFieldInsn(GETSTATIC,
                "java/util/concurrent/TimeUnit",
                "MILLISECONDS",
                "Ljava/util/concurrent/TimeUnit;");
        mv.visitTypeInsn(NEW, "java/util/concurrent/LinkedBlockingQueue");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/LinkedBlockingQueue", "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESPECIAL,
                "org/apache/tomcat/util/threads/ThreadPoolExecutor",
                "<init>",
                "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;)V",
                false);
        mv.visitTypeInsn(CHECKCAST, "java/util/concurrent/ExecutorService");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(8, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    static class TestClassLoader extends ClassLoader {
        Class<?> define(String fqcn, byte[] bytes) {
            return defineClass(fqcn, bytes, 0, bytes.length);
        }
    }
}
