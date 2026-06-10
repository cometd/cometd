import { CometD, Configuration } from "./cometd";

/**
 * Base class with the common functionality for transports.
 */
export abstract class Transport {
    url: string;

    /**
     * Function invoked just after a transport has been successfully registered.
     * @param type the type of transport (for example "long-polling")
     * @param cometd the cometd object this transport has been registered to
     * @see #unregistered()
     */
    registered(type: string, cometd: CometD): void;

    /**
     * Function invoked just after a transport has been successfully unregistered.
     * @see #registered(type, cometd)
     */
    unregistered(): void;

    readonly cometd: CometD;
    readonly configuration: Configuration;

    /**
     * Returns whether this transport can work for the given version and cross domain communication case.
     * @param version a string indicating the transport version
     * @param crossDomain a boolean indicating whether the communication is cross domain
     * @param url the URL to connect to
     * @return true if this transport can work for the given version and cross domain communication case,
     * false otherwise
     */
    accept(version: string, crossDomain: boolean, url: string): boolean;

    /**
     * Returns the type of this transport.
     * @see #registered(type, cometd)
     */
    readonly type: string;

    reset(init: boolean): void;

    abort(): void;
}
