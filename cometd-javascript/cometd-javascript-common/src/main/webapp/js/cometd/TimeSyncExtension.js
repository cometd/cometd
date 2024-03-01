/**
 * With each handshake or connect, the extension sends timestamps within the
 * ext field like: <code>{ext:{timesync:{tc:12345567890,l:23,o:4567},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp in ms since 1970 of when the message was sent.
 *  <li>l is the network lag that the client has calculated.
 *  <li>o is the clock offset that the client has calculated.
 * </ul>
 *
 * <p>
 * A CometD server that supports timesync, can respond with an ext
 * field like: <code>{ext:{timesync:{tc:12345567890,ts:1234567900,p:123,a:3},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp of when the message was sent,
 *  <li>ts is the server timestamp of when the message was received
 *  <li>p is the poll duration in ms - ie the time the server took before sending the response.
 *  <li>a is the measured accuracy of the calculated offset and lag sent by the client
 * </ul>
 *
 * <p>
 * The relationship between tc, ts & l is given by <code>ts=tc+o+l</code> (the
 * time the server received the message is the client time plus the offset plus the
 * network lag).   Thus the accuracy of the o and l settings can be determined with
 * <code>a=(tc+o+l)-ts</code>.
 * </p>
 * <p>
 * When the client has received the response, it can make a more accurate estimate
 * of the lag as <code>l2=(now-tc-p)/2</code> (assuming symmetric lag).
 * A new offset can then be calculated with the relationship on the client
 * that <code>ts=tc+o2+l2</code>, thus <code>o2=ts-tc-l2</code>.
 * </p>
 * <p>
 * Since the client also receives the a value calculated on the server, it
 * should be possible to analyse this and compensate for some asymmetry
 * in the lag. But the current client does not do this.
 * </p>
 *
 * @param configuration
 */
export function TimeSyncExtension(configuration) {
  let _cometd;
  const _maxSamples = (configuration && configuration.maxSamples) || 10;
  let _lags = [];
  let _offsets = [];
  let _lag = 0;
  let _offset = 0;

  function _debug(text, args) {
    _cometd._debug(text, args);
  }

  this.registered = (name, cometd) => {
    _cometd = cometd;
    _debug("TimeSyncExtension: executing registration callback");
  };

  this.unregistered = () => {
    _debug("TimeSyncExtension: executing unregistration callback");
    _cometd = null;
    _lags = [];
    _offsets = [];
  };

  this.incoming = (message) => {
    const channel = message.channel;
    if (channel && channel.indexOf("/meta/") === 0) {
      if (message.ext && message.ext.timesync) {
        const timesync = message.ext.timesync;
        _debug("TimeSyncExtension: server sent timesync", timesync);

        const now = new Date().getTime();
        const l2 = (now - timesync.tc - timesync.p) / 2;
        const o2 = timesync.ts - timesync.tc - l2;

        _lags.push(l2);
        _offsets.push(o2);
        if (_offsets.length > _maxSamples) {
          _offsets.shift();
          _lags.shift();
        }

        const samples = _offsets.length;
        let lagsSum = 0;
        let offsetsSum = 0;
        for (let i = 0; i < samples; ++i) {
          lagsSum += _lags[i];
          offsetsSum += _offsets[i];
        }
        _lag = parseInt((lagsSum / samples).toFixed());
        _offset = parseInt((offsetsSum / samples).toFixed());
        _debug(
          "TimeSyncExtension: network lag",
          _lag,
          "ms, time offset with server",
          _offset,
          "ms",
          _lag,
          _offset
        );
      }
    }
    return message;
  };

  this.outgoing = (message) => {
    const channel = message.channel;
    if (channel && channel.indexOf("/meta/") === 0) {
      if (!message.ext) {
        message.ext = {};
      }
      message.ext.timesync = {
        tc: new Date().getTime(),
        l: _lag,
        o: _offset,
      };
      _debug(
        "TimeSyncExtension: client sending timesync",
        message.ext.timesync
      );
    }
    return message;
  };

  /**
   * Get the estimated offset in ms from the clients clock to the
   * servers clock.  The server time is the client time plus the offset.
   */
  this.getTimeOffset = () => _offset;

  /**
   * Get an array of multiple offset samples used to calculate
   * the offset.
   */
  this.getTimeOffsetSamples = () => _offsets;

  /**
   * Get the estimated network lag in ms from the client to the server.
   */
  this.getNetworkLag = () => _lag;

  /**
   * Get the estimated server time in ms since the epoch.
   */
  this.getServerTime = () => new Date().getTime() + _offset;

  /**
   *
   * Get the estimated server time as a Date object
   */
  this.getServerDate = function () {
    return new Date(this.getServerTime());
  };

  /**
   * Set a timeout to expire at given time on the server.
   * @param callback The function to call when the timer expires
   * @param atServerTimeOrDate a js Time or Date object representing the
   * server time at which the timeout should expire
   */
  this.setTimeout = (callback, atServerTimeOrDate) => {
    const ts =
      atServerTimeOrDate instanceof Date
        ? atServerTimeOrDate.getTime()
        : 0 + atServerTimeOrDate;
    const tc = ts - _offset;
    let interval = tc - new Date().getTime();
    if (interval <= 0) {
      interval = 1;
    }
    return _cometd.setTimeout(callback, interval);
  };
}
