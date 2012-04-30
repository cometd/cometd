if (typeof dojo !== 'undefined' && !dojo.isAsync)
{
    dojo.provide('org.cometd');
}
else
{
    // Namespaces for the cometd implementation
    this.org = this.org || {};
	org.cometd = org.cometd || {};

	if (typeof define === "function" && define.amd) {
		define(function () {
			return org.cometd;
		});
	}
}
