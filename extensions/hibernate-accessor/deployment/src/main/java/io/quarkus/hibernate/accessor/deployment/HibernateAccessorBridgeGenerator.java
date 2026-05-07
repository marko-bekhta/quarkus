package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.CREATE_METHOD;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.READ_METHOD;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.WRITE_METHOD;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates public bridge classes for non-public (package-private) host entity classes.
 * <p>
 * The generated singleton reader/writer/instantiator impls live in a fixed runtime package
 * and cannot directly call static methods on package-private classes. This generator creates
 * a synthetic public class ({@code OriginalClass$$HibernateAccessorBridge}) in the same package
 * as the host, containing public static forwarding methods that delegate to the host's injected
 * {@code $$__hibernateRead}, {@code $$__hibernateWrite}, and {@code $$__hibernateCreate} methods.
 */
class HibernateAccessorBridgeGenerator implements Opcodes {

    static final String BRIDGE_SUFFIX = "$$HibernateAccessorBridge";

    static String bridgeFqcn(String hostFqcn) {
        return hostFqcn + BRIDGE_SUFFIX;
    }

    byte[] generate(String hostFqcn, boolean hasReaders, boolean hasWriters, boolean hasConstructors) {
        String bridgeName = fqcnToName(bridgeFqcn(hostFqcn));
        String hostName = fqcnToName(hostFqcn);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_SYNTHETIC, bridgeName,
                null, "java/lang/Object", null);

        if (hasReaders) {
            generateForward(cw, READ_METHOD,
                    "(ILjava/lang/Object;)Ljava/lang/Object;", hostName, false);
        }
        if (hasWriters) {
            generateForward(cw, WRITE_METHOD,
                    "(ILjava/lang/Object;Ljava/lang/Object;)V", hostName, true);
        }
        if (hasConstructors) {
            generateForward(cw, CREATE_METHOD,
                    "(I[Ljava/lang/Object;)Ljava/lang/Object;", hostName, false);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    // Generates a public static synthetic method that loads all args and forwards to the host.
    private static void generateForward(ClassWriter cw, String methodName, String descriptor,
            String hostName, boolean returnsVoid) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                methodName, descriptor, null, null);
        mv.visitCode();

        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        for (int i = 0, slot = 0; i < argTypes.length; i++) {
            mv.visitVarInsn(argTypes[i].getOpcode(ILOAD), slot);
            slot += argTypes[i].getSize();
        }

        mv.visitMethodInsn(INVOKESTATIC, hostName, methodName, descriptor, false);

        if (returnsVoid) {
            mv.visitInsn(RETURN);
        } else {
            mv.visitInsn(ARETURN);
        }

        mv.visitMaxs(argTypes.length, argTypes.length);
        mv.visitEnd();
    }
}
