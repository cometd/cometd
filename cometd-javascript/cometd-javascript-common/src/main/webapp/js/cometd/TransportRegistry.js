/**
 * A registry for transports used by the CometD object.
 */
export function TransportRegistry() {
  let _types = [];
  let _transports = {};

  this.getTransportTypes = () => _types.slice(0);

  this.findTransportTypes = (version, crossDomain, url) => {
    const result = [];
    for (let i = 0; i < _types.length; ++i) {
      const type = _types[i];
      if (_transports[type].accept(version, crossDomain, url) === true) {
        result.push(type);
      }
    }
    return result;
  };

  this.negotiateTransport = (types, version, crossDomain, url) => {
    for (let i = 0; i < _types.length; ++i) {
      const type = _types[i];
      for (let j = 0; j < types.length; ++j) {
        if (type === types[j]) {
          const transport = _transports[type];
          if (transport.accept(version, crossDomain, url) === true) {
            return transport;
          }
        }
      }
    }
    return null;
  };

  this.add = (type, transport, index) => {
    let existing = false;
    for (let i = 0; i < _types.length; ++i) {
      if (_types[i] === type) {
        existing = true;
        break;
      }
    }

    if (!existing) {
      if (typeof index !== "number") {
        _types.push(type);
      } else {
        _types.splice(index, 0, type);
      }
      _transports[type] = transport;
    }

    return !existing;
  };

  this.find = (type) => {
    for (let i = 0; i < _types.length; ++i) {
      if (_types[i] === type) {
        return _transports[type];
      }
    }
    return null;
  };

  this.remove = (type) => {
    for (let i = 0; i < _types.length; ++i) {
      if (_types[i] === type) {
        _types.splice(i, 1);
        const transport = _transports[type];
        delete _transports[type];
        return transport;
      }
    }
    return null;
  };

  this.clear = () => {
    _types = [];
    _transports = {};
  };

  this.reset = (init) => {
    for (let i = 0; i < _types.length; ++i) {
      _transports[_types[i]].reset(init);
    }
  };
}
