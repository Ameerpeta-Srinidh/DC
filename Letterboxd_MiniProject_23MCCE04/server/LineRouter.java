package server;

/**
 * LineRouter — the one method any "takes one line of JSON in, returns one
 * line of JSON out" component must implement.
 *
 * Both RequestRouter (client-facing: REGISTER/LOGIN/LOG_FILM/...) and
 * InternalRouter (backend-facing: the App-Server <-> DatabaseServer
 * protocol) implement this SAME interface. That's what lets ConnectionHandler
 * below be written ONCE and reused for both the public-facing App Server
 * and the internal DatabaseServer — the transport code (read a line, call
 * the router, write a line, repeat) genuinely does not care what the lines
 * mean, only that there's a request line in and a response line out. This
 * is the same idea as HTTP: the transport layer is oblivious to what a
 * particular route handler does.
 */
public interface LineRouter {
    String route(String requestLine);
}
