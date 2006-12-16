dojo.provide("dojopong.Player");

dojopong.Player = function()
{
	this.isConnected = false;
	this.isReady = false;
	this.paddle = null;
	this.playerId = null;
}

dojo.extend(dojopong.Player, {
	init: function()
	{
	
	},
	
	setPlayer: function (playerId, paddle, isUserControlled)
	{
		this.playerId = playerId;
		this.paddle = paddle;
		// @FIXME: _side is 0 based but player isn't.... wtf
		this.paddle._side = playerId - 1;
		this.paddle.isUserControlled = isUserControlled;
	},
	
	setReady: function()
	{
		dojo.debug("set ready!");
		cometd.publish("/dojopong/ready", { msg: "ready", source: cometd.clientId })
	}
});
