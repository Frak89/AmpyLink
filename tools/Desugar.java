/*
 * Minimal lambda desugarer for builds without D8: rewrites every invokedynamic that targets
 * java.lang.invoke.LambdaMetafactory (which does not exist on Android) into a plain synthetic
 * class (Outer$$Lambda$N) implementing the functional interface, created by a static factory.
 * Private lambda bodies / method-ref targets are made package-private so the synthetic class can
 * call them. Usage: java -cp asm.jar:tools Desugar <classes-dir>
 */
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Desugar {
    static final String LMF = "java/lang/invoke/LambdaMetafactory";
    static final Set<String> deprivatize = new HashSet<>();   // owner.name.desc
    static int generated = 0, rewritten = 0;

    public static void main(String[] a) throws IOException {
        Path root = Paths.get(a[0]);
        List<Path> files = new ArrayList<>();
        Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        // pass 1: find impl methods that must lose ACC_PRIVATE
        for (Path p : files) {
            new ClassReader(Files.readAllBytes(p)).accept(new ClassVisitor(Opcodes.ASM7) {
                @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM7) {
                        @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                            if (bsm.getOwner().equals(LMF)) {
                                Handle impl = (Handle) args[1];
                                deprivatize.add(impl.getOwner() + "." + impl.getName() + impl.getDesc());
                            }
                        }
                    };
                }
            }, 0);
        }
        // pass 2: rewrite
        for (Path p : files) {
            ClassReader cr = new ClassReader(Files.readAllBytes(p));
            ClassWriter cw = new ClassWriter(cr, 0);
            final String[] owner = new String[1];
            final List<byte[]> extra = new ArrayList<>();
            final int[] counter = {0};
            cr.accept(new ClassVisitor(Opcodes.ASM7, cw) {
                @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) {
                    owner[0] = name; super.visit(v, acc, name, sig, sup, itf);
                }
                @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
                    if ((acc & Opcodes.ACC_PRIVATE) != 0 && deprivatize.contains(owner[0] + "." + n + d)) acc &= ~Opcodes.ACC_PRIVATE;
                    return new MethodVisitor(Opcodes.ASM7, super.visitMethod(acc, n, d, s, e)) {
                        @Override public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                            // calls to a method that is no longer private must not stay invokespecial (dx → invoke-direct)
                            if (op == Opcodes.INVOKESPECIAL && !mn.equals("<init>") && o.equals(owner[0]) && deprivatize.contains(o + "." + mn + md)) op = Opcodes.INVOKEVIRTUAL;
                            super.visitMethodInsn(op, o, mn, md, itf);
                        }
                        @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                            if (!bsm.getOwner().equals(LMF)) { super.visitInvokeDynamicInsn(name, desc, bsm, args); return; }
                            String cls = owner[0] + "$$Lambda$" + (counter[0]++);
                            extra.add(lambdaClass(cls, name, desc, bsm, args));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, cls, "create", desc, false);
                            rewritten++;
                        }
                    };
                }
            }, 0);
            Files.write(p, cw.toByteArray());
            for (int i = 0; i < extra.size(); i++) {
                String simple = owner[0].substring(owner[0].lastIndexOf('/') + 1) + "$$Lambda$" + i + ".class";
                Files.write(p.resolveSibling(simple), extra.get(i));
                generated++;
            }
        }
        System.out.println("desugar: " + rewritten + " lambda sites, " + generated + " classes, " + deprivatize.size() + " targets");
    }

    static byte[] lambdaClass(String cls, String samName, String factoryDesc, Handle bsm, Object[] args) {
        Type samType = (Type) args[0];
        Handle impl = (Handle) args[1];
        Type inst = (Type) args[2];
        List<String> itfs = new ArrayList<>();
        Type factory = Type.getMethodType(factoryDesc);
        itfs.add(factory.getReturnType().getInternalName());
        List<Type> bridges = new ArrayList<>();
        if (bsm.getName().equals("altMetafactory")) {
            int flags = (Integer) args[3], k = 4;
            if ((flags & 2) != 0) { int n = (Integer) args[k++]; for (int i = 0; i < n; i++) itfs.add(((Type) args[k++]).getInternalName()); }
            if ((flags & 4) != 0) { int n = (Integer) args[k++]; for (int i = 0; i < n; i++) bridges.add((Type) args[k++]); }
        }
        Type[] cap = factory.getArgumentTypes();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, cls, null, "java/lang/Object", itfs.toArray(new String[0]));
        for (int i = 0; i < cap.length; i++) cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "a" + i, cap[i].getDescriptor(), null, null).visitEnd();
        String ctorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, cap);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", ctorDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 1;
        for (int i = 0; i < cap.length; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(cap[i].getOpcode(Opcodes.ILOAD), slot);
            mv.visitFieldInsn(Opcodes.PUTFIELD, cls, "a" + i, cap[i].getDescriptor());
            slot += cap[i].getSize();
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
        // factory
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "create", factoryDesc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, cls);
        mv.visitInsn(Opcodes.DUP);
        slot = 0;
        for (Type t : cap) { mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot); slot += t.getSize(); }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cls, "<init>", ctorDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
        // SAM + bridges
        samMethod(cw, cls, samName, samType, inst, impl, cap, Opcodes.ACC_PUBLIC);
        for (Type b : bridges) if (!b.getDescriptor().equals(samType.getDescriptor()))
            samMethod(cw, cls, samName, b, inst, impl, cap, Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static void samMethod(ClassWriter cw, String cls, String name, Type sam, Type inst, Handle impl, Type[] cap, int acc) {
        MethodVisitor mv = cw.visitMethod(acc, name, sam.getDescriptor(), null, null);
        mv.visitCode();
        int tag = impl.getTag();
        Type implM = Type.getMethodType(impl.getDesc());
        Type ownerT = Type.getObjectType(impl.getOwner());
        List<Type> target = new ArrayList<>();
        if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_INVOKESPECIAL) target.add(ownerT);
        target.addAll(Arrays.asList(implM.getArgumentTypes()));
        if (tag == Opcodes.H_NEWINVOKESPECIAL) { mv.visitTypeInsn(Opcodes.NEW, impl.getOwner()); mv.visitInsn(Opcodes.DUP); }
        Type[] sp = sam.getArgumentTypes(), ip = inst.getArgumentTypes();
        if (cap.length + sp.length != target.size()) throw new IllegalStateException("arity " + cls + " " + impl);
        int k = 0;
        for (int i = 0; i < cap.length; i++, k++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, cls, "a" + i, cap[i].getDescriptor());
            convert(mv, cap[i], target.get(k));
        }
        int slot = 1;
        for (int i = 0; i < sp.length; i++, k++) {
            mv.visitVarInsn(sp[i].getOpcode(Opcodes.ILOAD), slot);
            slot += sp[i].getSize();
            Type mid = i < ip.length ? ip[i] : sp[i];
            convert(mv, sp[i], mid);
            convert(mv, mid, target.get(k));
        }
        Type ret;
        switch (tag) {
            case Opcodes.H_INVOKESTATIC:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, impl.getOwner(), impl.getName(), impl.getDesc(), impl.isInterface()); ret = implM.getReturnType(); break;
            case Opcodes.H_NEWINVOKESPECIAL:
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, impl.getOwner(), "<init>", impl.getDesc(), false); ret = ownerT; break;
            case Opcodes.H_INVOKEINTERFACE:
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, impl.getOwner(), impl.getName(), impl.getDesc(), true); ret = implM.getReturnType(); break;
            default: // virtual, or special on a (now package-private) private method
                mv.visitMethodInsn(impl.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, impl.getOwner(), impl.getName(), impl.getDesc(), impl.isInterface());
                ret = implM.getReturnType();
        }
        Type sr = sam.getReturnType();
        if (sr.getSort() == Type.VOID) {
            if (ret.getSize() == 2) mv.visitInsn(Opcodes.POP2); else if (ret.getSize() == 1) mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        } else {
            Type ir = inst.getReturnType();
            if (ir.getSort() != Type.VOID) { convert(mv, ret, ir); convert(mv, ir, sr); } else convert(mv, ret, sr);
            mv.visitInsn(sr.getOpcode(Opcodes.IRETURN));
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static boolean prim(Type t) { return t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE; }

    static String box(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean"; case Type.CHAR: return "java/lang/Character";
            case Type.BYTE: return "java/lang/Byte"; case Type.SHORT: return "java/lang/Short";
            case Type.INT: return "java/lang/Integer"; case Type.FLOAT: return "java/lang/Float";
            case Type.LONG: return "java/lang/Long"; default: return "java/lang/Double";
        }
    }

    static Type unboxed(String internal) {
        switch (internal) {
            case "java/lang/Boolean": return Type.BOOLEAN_TYPE; case "java/lang/Character": return Type.CHAR_TYPE;
            case "java/lang/Byte": return Type.BYTE_TYPE; case "java/lang/Short": return Type.SHORT_TYPE;
            case "java/lang/Integer": return Type.INT_TYPE; case "java/lang/Float": return Type.FLOAT_TYPE;
            case "java/lang/Long": return Type.LONG_TYPE; case "java/lang/Double": return Type.DOUBLE_TYPE;
            default: return null;
        }
    }

    static void convert(MethodVisitor mv, Type from, Type to) {
        if (from.equals(to) || to.getSort() == Type.VOID) return;
        if (prim(from) && prim(to)) { widen(mv, from, to); return; }
        if (prim(from)) { // box
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, box(from), "valueOf", "(" + from.getDescriptor() + ")L" + box(from) + ";", false);
            if (!to.getInternalName().equals("java/lang/Object") && !to.getInternalName().equals(box(from))) mv.visitTypeInsn(Opcodes.CHECKCAST, to.getInternalName());
            return;
        }
        if (prim(to)) { // unbox
            Type u = unboxed(from.getInternalName());
            String w = u != null ? from.getInternalName() : box(to);
            if (u == null) { mv.visitTypeInsn(Opcodes.CHECKCAST, w); u = to; }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, w, u.getClassName() + "Value", "()" + u.getDescriptor(), false);
            widen(mv, u, to);
            return;
        }
        if (!to.getInternalName().equals("java/lang/Object")) mv.visitTypeInsn(Opcodes.CHECKCAST, to.getSort() == Type.ARRAY ? to.getDescriptor() : to.getInternalName());
    }

    static void widen(MethodVisitor mv, Type f, Type t) {
        int fs = f.getSort() <= Type.INT ? Type.INT : f.getSort(), ts = t.getSort() <= Type.INT ? Type.INT : t.getSort();
        if (fs == ts) return;
        if (fs == Type.INT) mv.visitInsn(ts == Type.LONG ? Opcodes.I2L : ts == Type.FLOAT ? Opcodes.I2F : Opcodes.I2D);
        else if (fs == Type.LONG) mv.visitInsn(ts == Type.FLOAT ? Opcodes.L2F : Opcodes.L2D);
        else if (fs == Type.FLOAT) mv.visitInsn(Opcodes.F2D);
    }
}
