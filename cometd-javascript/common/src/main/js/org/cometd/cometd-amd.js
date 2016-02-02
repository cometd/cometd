if (typeof define === 'function' && define.amd) {
    define([
      './Utils',
      './TransportRegistry',
      './Transport',
      './RequestTransport',
      './LongPollingTransport',
      './CallbackPollingTransport',
      './WebSocketTransport',
      './CometD'
    ], function(
          Utils,
          TransportRegistry,
          Transport,
          RequestTransport,
          LongPollingTransport,
          CallbackPollingTransport,
          WebSocketTransport,
          CometD
        ) {
        return {
            Utils: Utils,
            TransportRegistry: TransportRegistry,
            Transport: Transport,
            RequestTransport: RequestTransport,
            LongPollingTransport: LongPollingTransport,
            CallbackPollingTransport: CallbackPollingTransport,
            WebSocketTransport: WebSocketTransport,
            CometD: CometD
        };
    });
}
