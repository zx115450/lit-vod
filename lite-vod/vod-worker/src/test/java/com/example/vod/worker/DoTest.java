package com.example.vod.worker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class DoTest {

	@Test
	void test() throws IOException {
		String command = "";
		Path workdir = Path.of(System.getProperty("user.dir"));
		Duration timeout = Duration.ofSeconds(10);
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(workdir.toFile());
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();

		Thread thread = new Thread(new OutputReader(process.getInputStream()) , "thread - read");
		thread.setDaemon(true);
		thread.start();

		boolean timedOut = false;
		InterruptedException interrupted = null;

		boolean finished = true;
		try {
			finished = process.waitFor(timeout.toSeconds() , TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			interrupted = e;
		}
		//超时了
		if (!finished) {
			timedOut = true;
			process.destroyForcibly();
			try {
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					System.out.println("process timed out");
				}
			} catch (InterruptedException e) {
				interrupted = e;
			}
		}
		boolean is = Thread.currentThread().isInterrupted();
		try {
			thread.join(5000);
			if (thread.isAlive()) {
				timedOut = true;
			}
		} catch (InterruptedException e) {
			interrupted = e;
		} finally {
			if (is) Thread.currentThread().interrupt();
		}

	}
	public class OutputReader implements Runnable {

		private final InputStream in;
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();
		private volatile IOException error;

		public OutputReader(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			try (in) {
				int n = 0;
				byte [] buffer = new byte[1024];
				while ((n = in.read()) >= 0) {
					out.write(buffer, 0, in.read());
				}
			} catch (IOException e) {
				error = e;
			}
		}

		private String getOutput() {
			return new String(out.toByteArray());
		}

		private IOException getError() {
			return error;
		}

	}

}
