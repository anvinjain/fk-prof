/**
 * Copyright (c) 2014 Richard Warburton (richard.warburton@gmail.com)
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 **/
package fk.prof.recorder.utils;

import fk.prof.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AgentRunner {
    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    public static final String DEFAULT_AGENT_INTERVAL = "interval=100";

    private final String fqdn;
    private final String args;

    private Process process;
    private int processId;

    public AgentRunner(final String fqdn, final String args) {
        this.fqdn = fqdn;
        this.args = args;
    }

    public static void run(final String className, final Consumer<AgentRunner> handler) throws IOException {
        run(className, (String) null, handler);
    }

    public static void run(final String className,
                           final String[] args,
                           final Consumer<AgentRunner> handler) throws IOException {
        run(className, String.join(",", args), handler);
    }

    public static void run(final String className,
                           final String args,
                           final Consumer<AgentRunner> handler) throws IOException {
        AgentRunner runner = new AgentRunner(className, args);
        runner.start();
        try {
            handler.accept(runner);
        } finally {
            runner.stop();
        }
    }

    public void start() throws IOException {
        startProcess();
        //readProcessId();
    }

    private void startProcess() throws IOException {
        String java = System.getProperty("java.home") + "/bin/java";
        String agentArg = "-agentpath:../recorder/build/libfkpagent" + Platforms.getDynamicLibraryExtension() + (args != null ? "=" + args : "");
        // Eg: java -agentpath:build/liblagent.so -cp target/classes/ InfiniteExample

        // manually setting classpath
        List<String> classpath = Arrays.asList("../recorder/target/classes/", "target/test-classes/:target/lib/*");
        //System.out.println("classpath = " + classpath);
        process = new ProcessBuilder()
                .command("java", agentArg, "-cp", String.join(":", classpath), fqdn)
                .redirectError(new File("/tmp/fkprof_stderr.log"))
                .redirectOutput(new File("/tmp/fkprof_stdout.log"))
                .start();
    }

    private List<String> discoverClasspath(Class klass) {
        ClassLoader loader = klass.getClassLoader();
        List<String> classPath = new ArrayList<>();
        do {
            if (loader instanceof URLClassLoader) {
                URLClassLoader urlClassLoader = (URLClassLoader) loader;
                URL[] urLs = urlClassLoader.getURLs();
                for (URL urL : urLs) {
                    classPath.add(urL.toString());
                }
            }
            loader = loader.getParent();
        } while (loader != null);
        return classPath;
    }

    public void stop() {
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.info(e.getMessage(), e);
        }
    }

    public int getProcessId() {
        return processId;
    }

    public void startProfiler() {
        messageProcess('S');
    }

    public void stopProfiler() {
        messageProcess('s');
    }

    private void messageProcess(final char message) {
        try {
            final OutputStream outputStream = process.getOutputStream();
            outputStream.write(message);
            outputStream.write('\n');
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
