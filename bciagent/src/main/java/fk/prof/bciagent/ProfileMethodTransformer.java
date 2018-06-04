package fk.prof.bciagent;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Function;

public class ProfileMethodTransformer implements ClassFileTransformer {

  private final Map<String, ClassInstrumentHooks> INSTRUMENTED_CLASSES = new HashMap<>();
  private final ClassPool pool;
  private final CtClass threadCtClass;
  private final String currThreadVarName = "$$$_curr_thread";

  /**
   * notifies the recorder that the bci failed for the provided class. Disables the io tracing if called at least once.
   *
   * @param className
   */
  private static native void bciFailed(String className);

  public ProfileMethodTransformer() throws NotFoundException {
    pool = ClassPool.getDefault();
    threadCtClass = pool.get("java.lang.Thread");
  }

  public boolean init() {
    String klass;
    ClassInstrumentHooks hooks;

    EntryExitHooks<CtMethod> fs_open = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::fs_open);
    EntryExitHooks<CtMethod> fs_read = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::fs_read);
    Function<Integer, EntryExitHooks<CtMethod>> fs_write =
        i -> new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, m -> Instrumenter.MethodExit.fs_write(m, i));

    klass = "java.io.FileInputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("open(Ljava/lang/String;)V", fs_open);
    hooks.methods.put("read()I", fs_read);
    hooks.methods.put("read([B)I", fs_read);
    hooks.methods.put("read([BII)I", fs_read);

    klass = "java.io.FileOutputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("open(Ljava/lang/String;Z)V", fs_open);
    hooks.methods.put("write(I)V", fs_write.apply(1));
    hooks.methods.put("write([B)V", fs_write.apply(2));
    hooks.methods.put("write([BII)V", fs_write.apply(3));

    CtClass socketTimeoutExClass;
    try {
      socketTimeoutExClass = pool.get("java.net.SocketTimeoutException");
    } catch (NotFoundException e) {
      System.err.println(e.getMessage());
      return false;
    }

    EntryExitHooks<CtMethod> sock_input = new EntryExitHooks<>(Instrumenter.MethodEntry::ss_read, m -> Instrumenter.MethodExit.ss_read(m, socketTimeoutExClass));
    EntryExitHooks<CtMethod> sock_output = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::ss_write);
    EntryExitHooks<CtMethod> sock_connect = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::sock_connect);
    EntryExitHooks<CtMethod> sock_accept = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::sock_accept);

    klass = "java.net.SocketInputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("read([BIII)I", sock_input);
//    hooks.methods.put("skip(J)J", sock_input);
//    hooks.methods.put("available()I", sock_input);

    klass = "java.net.SocketOutputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("socketWrite([BII)V", sock_output);

    klass = "java.net.Socket";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("connect(Ljava/net/SocketAddress;I)V", sock_connect);

    klass = "java.net.ServerSocket";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("accept()Ljava/net/Socket;", sock_accept);

    EntryExitHooks<CtMethod> sock_ch_connect = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::sockCh_connect);
    EntryExitHooks<CtConstructor> sock_ch_ctr = new EntryExitHooks<>(Instrumenter.ConstructorEntry::elapsed, Instrumenter.ConstructorExit::sockCh);

    klass = "sun.nio.ch.SocketChannelImpl";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("connect(Ljava/net/SocketAddress;)Z", sock_ch_connect);
    hooks.constructors.put("SocketChannelImpl(Ljava/nio/channels/spi/SelectorProvider;Ljava/io/FileDescriptor;Ljava/net/InetSocketAddress;)V", sock_ch_ctr);

    EntryExitHooks<CtMethod> ioutil_read = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::ioutil_read);
    EntryExitHooks<CtMethod> ioutil_write = new EntryExitHooks<>(Instrumenter.MethodEntry::elapsed, Instrumenter.MethodExit::ioutil_write);

    klass = "sun.nio.ch.IOUtil";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("write(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_write);
    hooks.methods.put("write(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_write);
    hooks.methods.put("read(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_read);
    hooks.methods.put("read(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_read);

    return true;
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    try {
      if (className == null) {
        return null;
      }

      String normalizedClassName = className.replaceAll("/", ".");
      pool.insertClassPath(new ByteArrayClassPath(normalizedClassName, classfileBuffer));

      if(INSTRUMENTED_CLASSES.get(normalizedClassName) == null && !normalizedClassName.equals("java.lang.Thread")) {
        return null;
      }

      CtClass cclass = pool.get(normalizedClassName);
      if(className.equals("java/lang/Thread")) {
        transformThread(cclass);
        cclass.debugWriteFile("/home/gaurav.ashok/Documents/work/temp/classFilesDump");
        return cclass.toBytecode();
      }

      boolean modified = false;
      if (!cclass.isFrozen()) {
        ClassInstrumentHooks instrumentHooks = INSTRUMENTED_CLASSES.get(cclass.getName());
        modified = instrumentHooks != null && instrumentHooks.apply(cclass);
      }

      if(modified) {
        return cclass.toBytecode();
      }
    } catch (Exception e) {
      System.err.println(e);
      e.printStackTrace();

      bciFailed(className);
    }
    return null;
  }

  private void transformThread(CtClass cclass) throws Exception {
    CtMethod m = Util.getMethod(cclass,
        "init",
        "(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;JLjava/security/AccessControlContext;Z)V");

    m.addLocalVariable(currThreadVarName, threadCtClass);

    m.instrument(new ExprEditor() {
      @Override
      public void edit(FieldAccess f) throws CannotCompileException {
        if(f.isWriter() && "name".equals(f.getFieldName())) {
          String code = String.format("{" +
              "%1$s = currentThread(); " +
              "if(%1$s.taskCtx != null) " +
              "{" +
                  "this.taskCtx = %1$s.taskCtx.addTask(\"java.lang\");" +
              "}" +
              "$proceed($$);" +
          "}", currThreadVarName);
          f.replace(code);
        }
        else if(f.isWriter() && "target".equals(f.getFieldName())) {
          String code = String.format("{" +
              "if (this.taskCtx == null) {" +
                  "$proceed($$);" +
              "}" +
              "else {" +
                  "$proceed(new fk.prof.WrappedThreadRunnable($1, this.taskCtx));" +
              "}" +
          "}", currThreadVarName);
          f.replace(code);
        }
      }
    });
  }
}