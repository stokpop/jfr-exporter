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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutorInstrumentation {

    private static final Logger log = Logger.getLogger(ExecutorInstrumentation.class);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final String EXECUTORS_OWNER = "java/util/concurrent/Executors";
    private static final String THREAD_POOL_EXECUTOR_OWNER = "java/util/concurrent/ThreadPoolExecutor";
    private static final String SCHEDULED_THREAD_POOL_EXECUTOR_OWNER = "java/util/concurrent/ScheduledThreadPoolExecutor";
    private static final String HOOKS_OWNER = "io/perfana/jfr/ExecutorInstrumentationHooks";

    private ExecutorInstrumentation() {
    }

    public static void install(Instrumentation instrumentation) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        instrumentation.addTransformer(new ExecutorDiscoveryTransformer(), instrumentation.isRetransformClassesSupported());
        log.info("Installed executor discovery instrumentation");
    }

    static ClassFileTransformer createTransformer() {
        return new ExecutorDiscoveryTransformer();
    }

    private static final class ExecutorDiscoveryTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
        }

        @Override
        public byte[] transform(Module module,
                                ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className == null || shouldIgnore(className)) {
                return null;
            }

            ClassReader classReader = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode();
            classReader.accept(classNode, ClassReader.EXPAND_FRAMES);

            boolean changed = false;
            for (MethodNode method : classNode.methods) {
                if (rewriteMethod(method)) {
                    changed = true;
                }
            }

            if (!changed) {
                return null;
            }

            ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
            classNode.accept(classWriter);
            return classWriter.toByteArray();
        }

        private static boolean shouldIgnore(String className) {
            return className.startsWith("java/")
                    || className.startsWith("javax/")
                    || className.startsWith("jdk/")
                    || className.startsWith("sun/")
                    || className.startsWith("com/sun/")
                    || className.startsWith("org/objectweb/asm/")
                    || className.startsWith("io/perfana/jfr/");
        }

        private static boolean rewriteMethod(MethodNode method) {
            boolean changed = false;

            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();

                if (instruction instanceof MethodInsnNode methodInsnNode) {
                    if (rewriteExecutorFactoryCall(methodInsnNode)) {
                        changed = true;
                    } else if (rewriteExecutorConstructor(method.instructions, methodInsnNode)) {
                        changed = true;
                    }
                }

                instruction = next;
            }

            return changed;
        }

        private static boolean rewriteExecutorFactoryCall(MethodInsnNode instruction) {
            if (instruction.getOpcode() != Opcodes.INVOKESTATIC || !EXECUTORS_OWNER.equals(instruction.owner)) {
                return false;
            }

            String replacementName = switch (instruction.name + instruction.desc) {
                case "newFixedThreadPool(I)Ljava/util/concurrent/ExecutorService;" -> "newFixedThreadPool";
                case "newFixedThreadPool(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;" -> "newFixedThreadPool";
                case "newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;" -> "newSingleThreadExecutor";
                case "newSingleThreadExecutor(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;" -> "newSingleThreadExecutor";
                case "newCachedThreadPool()Ljava/util/concurrent/ExecutorService;" -> "newCachedThreadPool";
                case "newCachedThreadPool(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;" -> "newCachedThreadPool";
                case "newSingleThreadScheduledExecutor()Ljava/util/concurrent/ScheduledExecutorService;" -> "newSingleThreadScheduledExecutor";
                case "newSingleThreadScheduledExecutor(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ScheduledExecutorService;" -> "newSingleThreadScheduledExecutor";
                case "newScheduledThreadPool(I)Ljava/util/concurrent/ScheduledExecutorService;" -> "newScheduledThreadPool";
                case "newScheduledThreadPool(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ScheduledExecutorService;" -> "newScheduledThreadPool";
                default -> null;
            };

            if (replacementName == null) {
                return false;
            }

            instruction.owner = HOOKS_OWNER;
            instruction.name = replacementName;
            return true;
        }

        private static boolean rewriteExecutorConstructor(org.objectweb.asm.tree.InsnList instructions, MethodInsnNode instruction) {
            if (instruction.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(instruction.name)) {
                return false;
            }

            String replacementName = constructorReplacementName(instruction.owner, instruction.desc);
            if (replacementName == null) {
                return false;
            }

            removeAllocationSequence(instructions, instruction.owner, instruction);

            MethodInsnNode replacement = new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HOOKS_OWNER,
                    replacementName,
                    constructorDescriptorWithReturnType(instruction.owner, instruction.desc),
                    false);
            instructions.set(instruction, replacement);
            return true;
        }

        private static String constructorReplacementName(String owner, String descriptor) {
            if (THREAD_POOL_EXECUTOR_OWNER.equals(owner)) {
                return switch (descriptor) {
                    case "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;)V",
                         "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;Ljava/util/concurrent/ThreadFactory;)V",
                         "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;Ljava/util/concurrent/RejectedExecutionHandler;)V",
                         "(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;Ljava/util/concurrent/ThreadFactory;Ljava/util/concurrent/RejectedExecutionHandler;)V" -> "newThreadPoolExecutor";
                    default -> null;
                };
            }

            if (SCHEDULED_THREAD_POOL_EXECUTOR_OWNER.equals(owner)) {
                return switch (descriptor) {
                    case "(I)V",
                         "(ILjava/util/concurrent/ThreadFactory;)V",
                         "(ILjava/util/concurrent/RejectedExecutionHandler;)V",
                         "(ILjava/util/concurrent/ThreadFactory;Ljava/util/concurrent/RejectedExecutionHandler;)V" -> "newScheduledThreadPoolExecutor";
                    default -> null;
                };
            }

            return null;
        }

        private static void removeAllocationSequence(org.objectweb.asm.tree.InsnList instructions,
                                                     String owner,
                                                     MethodInsnNode constructorCall) {
            AbstractInsnNode dupInstruction = null;
            TypeInsnNode newInstruction = null;

            for (AbstractInsnNode cursor = constructorCall.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
                if (dupInstruction == null && cursor.getOpcode() == Opcodes.DUP) {
                    dupInstruction = cursor;
                }
                if (cursor instanceof TypeInsnNode typeInsnNode
                        && typeInsnNode.getOpcode() == Opcodes.NEW
                        && owner.equals(typeInsnNode.desc)) {
                    newInstruction = typeInsnNode;
                    break;
                }
            }

            if (newInstruction != null) {
                instructions.remove(newInstruction);
            }
            if (dupInstruction != null) {
                instructions.remove(dupInstruction);
            }
        }

        private static String constructorDescriptorWithReturnType(String owner, String constructorDescriptor) {
            return constructorDescriptor.substring(0, constructorDescriptor.length() - 1) + "L" + owner + ";";
        }
    }
}
