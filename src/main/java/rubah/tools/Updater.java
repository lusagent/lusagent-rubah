/*******************************************************************************
 *  	Copyright 2014,
 *  		Luis Pina <luis@luispina.me>,
 *  		Michael Hicks <mwh@cs.umd.edu>
 *
 *  	This file is part of Rubah.
 *
 *     Rubah is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Rubah is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Rubah.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package rubah.tools;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.commons.io.output.NullOutputStream;

import rubah.Rubah;
import rubah.runtime.RubahRuntime;
import rubah.runtime.VersionManager;
import rubah.runtime.state.Installer;
import rubah.runtime.state.Options;
import rubah.tools.updater.ParsingArguments;
import rubah.tools.updater.UpdateState;
import rubah.update.FileUpdateClass;
import rubah.update.JarUpdateClass;
import rubah.update.UpdateClass;


public class Updater {
	private static final String LISTENER_THREAD_NAME = "rubahUpdateThread";
	private static final String DISABLE_OUT = "quietOut";
	public static final PrintStream out;
	private static final String PORT_PROP = "updatePort";
	public static final int port;

	static {
		if (System.getProperty(DISABLE_OUT) != null) {
			out = new PrintStream(new NullOutputStream());
		} else {
			out = System.out;
		}
		port = Integer.parseInt(System.getProperty(PORT_PROP, "55416"));
	}

	public static void main(String[] args) {
		UpdateState state = new UpdateState();
		state.setCommandLine(args);

		ParsingArguments parser = new ParsingArguments();
		parser.execute(state);

		UpdateClass updateClass;

		if (state.getUpdateClassName() != null)
			updateClass = new JarUpdateClass(state.getUpdateClassName());
		else if (state.getUpdateClassFile() != null)
			updateClass = new FileUpdateClass(state.getUpdateClassFile());
		else
			updateClass = null;

		Options options;
		Updater.Type type = null;

		if (state.isV0V0()) {
			type = Type.v0v0;
			options = new Options()
			.setUpdatePackage(state.getUpdatePackage())
			.setUpdateClass(updateClass)
			.setStopAndGo(state.isStopAndGo())
			.setMigrationStrategy(state.getMigrationStrategy())
			.setLazy(state.isLazy());
		} else {
			type = Type.v0v1;
			options = new Options()
			.setUpdatePackage(state.getUpdatePackage())
			.setUpdateDescriptor(state.getDescriptor())
			.setJar(state.getNewJar())
			.setUpdateClass(updateClass)
			.setStopAndGo(state.isStopAndGo())
			.setMigrationStrategy(state.getMigrationStrategy())
			.setLazy(state.isLazy());
		}

		try (Socket clientSocket = new Socket(InetAddress.getLocalHost(), Updater.port)) {
			installUpdate(type, options, clientSocket);
	    } catch (Exception e) {
	        System.err.println("Client Error: " + e.getMessage());
	        System.err.println("Localized: " + e.getLocalizedMessage());
	        System.err.println("Stack Trace: " + e.getStackTrace());
	    }
	}

	public enum Type { v0v0, v0v1 }

	private static void installUpdate(Type type, Options options, Socket clientSocket) throws IOException {
		// Create the input & output streams to the server
		ObjectOutputStream outToServer = new ObjectOutputStream(clientSocket.getOutputStream());
		outToServer.writeBoolean(false);
		outToServer.writeObject(type);
		outToServer.writeObject(options);
	}

	public static interface Observer {
		public boolean update(String updatePoint);
	}

	public static void addObserver(Type type, Options options, final Observer observer) {
		Socket cs = null;
		try {
			final Socket clientSocket = new Socket(InetAddress.getLocalHost(), Updater.port);
			cs = clientSocket;
	        // Create the input & output streams to the server
	        final ObjectOutputStream outToServer  = new ObjectOutputStream(clientSocket.getOutputStream());
	        final ObjectInputStream  inFromServer = new ObjectInputStream(clientSocket.getInputStream());
	        outToServer.writeBoolean(true);
	        outToServer.writeObject(type);
	        outToServer.writeObject(options);

	        Thread t = new Thread(){
				@Override
				public void run() {
					while (!clientSocket.isClosed()) {
						try {
							String updatePoint = (String) inFromServer.readObject();
							boolean update = observer.update(updatePoint);
							outToServer.writeBoolean(update);
							outToServer.flush();
						} catch (ClassNotFoundException | IOException e) {
							e.printStackTrace();
							continue;
						}
					}
				}
	        };

	        t.start();

	    } catch (Exception e) {
	        try {
				cs.close();
			} catch (IOException e1) {
				// Don't really care
			}

	        System.err.println("Client Error: " + e.getMessage());
	        System.err.println("Localized: " + e.getLocalizedMessage());
	        System.err.println("Stack Trace: " + e.getStackTrace());
	    }
	}

	private static class ListenerThread extends Thread {

		public ListenerThread(String name) {
			super(name);
			this.setDaemon(true);
		}

		@Override
		public void run() {
			try (ServerSocket welcomeSocket = new ServerSocket(port)) {
				while (true) {
					// Create the Client Socket
					try (Socket clientSocket = welcomeSocket.accept()) {
						out.println("Updater connected...");
						ObjectInputStream inFromClient = new ObjectInputStream(clientSocket.getInputStream());
						ObjectOutputStream outToClient = new ObjectOutputStream(clientSocket.getOutputStream());

						boolean observed = inFromClient.readBoolean();
						final Type type = (Type) inFromClient.readObject();
						final Options options = (Options) inFromClient.readObject();

						Installer installer = new Installer() {
							@Override
							public void installVersion() throws IOException {
								switch (type) {
								case v0v0:
									VersionManager.getInstance().installV0V0(options);
									break;
								case v0v1:
									VersionManager.getInstance().installVersion(options);
									break;
								default:
									throw new Error("Unknown update type");
								}
							}
						};

						try {
							if (observed) {
								RubahRemoteObserver observer = new RubahRemoteObserver(outToClient, inFromClient);
								while (true) {
									// TODO handle observer disconnecting
									RubahRuntime.observeState(observer);
									Rubah.installNewVersion(options, installer);
								}
							} else {
								Rubah.installNewVersion(options, installer);
							}
						} catch (Throwable e) {
							System.out.println(e);
							e.printStackTrace();
							continue;
						}
					} catch (ClassNotFoundException e) {
						System.out.println(e);
						e.printStackTrace();
						continue;
					}
				}
			} catch (IOException e) {
				throw new Error(e);
			}
		}

		private static class RubahRemoteObserver implements rubah.runtime.state.UpdateState.Observer {
			private final ObjectOutputStream outToClient;
			private final ObjectInputStream  inFromClient;

			public RubahRemoteObserver(ObjectOutputStream outToClient, ObjectInputStream inFromClient) {
				this.outToClient  = outToClient;
				this.inFromClient = inFromClient;
			}

			@Override
			public boolean update(String updatePoint) {
				synchronized (this) {

					boolean ret = false;
					try {
						outToClient.writeObject(updatePoint);
						outToClient.flush();
						ret = inFromClient.readBoolean();
					} catch (IOException e) {
						e.printStackTrace();
					}

					return ret;
				}
			}
		}
	}

	public static void listen() {
		new ListenerThread(LISTENER_THREAD_NAME).start();
	}
}
