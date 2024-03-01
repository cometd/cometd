/**
 * The reload extension allows a page to be loaded (or reloaded)
 * without having to re-handshake in the new (or reloaded) page,
 * therefore resuming the existing CometD connection.
 *
 * When the reload() method is called, the state of the CometD
 * connection is stored in the window.sessionStorage object.
 * The reload() method must therefore be called by page unload
 * handlers, often provided by JavaScript toolkits.
 *
 * When the page is (re)loaded, this extension checks the
 * window.sessionStorage and restores the CometD connection,
 * maintaining the same CometD clientId.
 */
export function ReloadExtension(configuration) {
  let _cometd;
  let _debug;
  let _state = {};
  let _name = "org.cometd.reload";
  let _batch = false;
  let _reloading = false;

  function _reload(config) {
    if (_state.handshakeResponse) {
      _reloading = true;
      const transport = _cometd.getTransport();
      if (transport) {
        transport.abort();
      }
      _configure(config);
      const state = JSON.stringify(_state);
      _debug("Reload extension saving state", state);
      window.sessionStorage.setItem(_name, state);
    }
  }

  function _similarState(oldState) {
    // We want to check here that the CometD object
    // did not change much between reloads.
    // We just check the URL for now, but in future
    // further checks may involve the transport type
    // and other configuration parameters.
    return _state.url === oldState.url;
  }

  function _configure(config) {
    if (config) {
      if (typeof config.name === "string") {
        _name = config.name;
      }
    }
  }

  function _receive(response) {
    _cometd.receive(response);
  }

  this.configure = _configure;

  this._receive = _receive;

  this.registered = (name, cometd) => {
    _cometd = cometd;
    _cometd.reload = _reload;
    _debug = _cometd._debug;
  };

  this.unregistered = () => {
    delete _cometd.reload;
    _cometd = null;
  };

  this.outgoing = function (message) {
    switch (message.channel) {
      case "/meta/handshake": {
        _state = {};
        _state.url = _cometd.getURL();

        const state = window.sessionStorage.getItem(_name);
        _debug("Reload extension found state", state);
        // Is there a saved handshake response from a prior load ?
        if (state) {
          try {
            const oldState = JSON.parse(state);

            // Remove the state, not needed anymore
            window.sessionStorage.removeItem(_name);

            if (oldState.handshakeResponse && _similarState(oldState)) {
              _debug("Reload extension restoring state", oldState);

              // Since we are going to abort this message,
              // we must save an eventual callback to restore
              // it when we replay the handshake response.
              const callback = _cometd._getCallback(message.id);

              setTimeout(() => {
                _debug(
                  "Reload extension replaying handshake response",
                  oldState.handshakeResponse
                );
                _state.handshakeResponse = oldState.handshakeResponse;
                _state.transportType = oldState.transportType;

                // Restore the callback.
                _cometd._putCallback(message.id, callback);

                const response = _cometd._mixin(
                  true,
                  {},
                  _state.handshakeResponse,
                  {
                    // Keep the response message id the same as the request.
                    id: message.id,
                    // Tells applications this is a handshake replayed by the reload extension.
                    ext: {
                      reload: true,
                    },
                  }
                );
                // Use the same transport as before.
                response.supportedConnectionTypes = [_state.transportType];

                this._receive(response);
                _debug(
                  "Reload extension replayed handshake response",
                  response
                );
              }, 0);

              // Delay any sends until first connect is complete.
              // This avoids that there is an old /meta/connect pending on server
              // that will be resumed to send messages to the client, when the
              // client has already closed the connection, thereby losing the messages.
              if (!_batch) {
                _batch = true;
                _cometd.startBatch();
              }

              // This handshake is aborted, as we will replay the prior handshake response
              return null;
            } else {
              _debug("Reload extension could not restore state", oldState);
            }
          } catch (x) {
            _debug("Reload extension error while trying to restore state", x);
          }
        }
        break;
      }
      case "/meta/connect": {
        if (_reloading === true) {
          // The reload causes the failure of the outstanding /meta/connect,
          // which CometD will react to by sending another. Here we avoid
          // that /meta/connect messages are sent between the reload and
          // the destruction of the JavaScript context, so that we are sure
          // that the first /meta/connect is the one triggered after the
          // replay of the /meta/handshake by this extension.
          _debug("Reload extension aborting /meta/connect during reload");
          return null;
        }

        if (!_state.transportType) {
          _state.transportType = message.connectionType;
          _debug(
            "Reload extension tracked transport type",
            _state.transportType
          );
        }
        break;
      }
      case "/meta/disconnect": {
        _state = {};
        break;
      }
      default: {
        break;
      }
    }
    return message;
  };

  this.incoming = (message) => {
    switch (message.channel) {
      case "/meta/handshake": {
        // Only record the handshake response if it's successful.
        if (message.successful) {
          // If the handshake response is already present, then we're replaying it.
          // Since the replay may have modified the handshake response, do not record it again.
          if (!_state.handshakeResponse) {
            // Save successful handshake response
            _state.handshakeResponse = message;
            _debug("Reload extension tracked handshake response", message);
          }
        }
        break;
      }
      case "/meta/connect": {
        if (_batch) {
          _batch = false;
          _cometd.endBatch();
        }
        break;
      }
      case "/meta/disconnect": {
        if (_batch) {
          _batch = false;
          _cometd.endBatch();
        }
        _state = {};
        break;
      }
      default: {
        break;
      }
    }
    return message;
  };

  _configure(configuration);
}
