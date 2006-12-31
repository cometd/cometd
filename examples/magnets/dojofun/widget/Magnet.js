dojo.provide("dojofun.widget.Magnet");
dojo.require("dojo.html.style");
dojo.require("dojo.lfx.html");
dojo.require("dojo.dnd.HtmlDragMove");


dojo.widget.defineWidget("dojofun.widget.Magnet", dojo.widget.HtmlWidget, { 
	templatePath: dojo.uri.dojoUri("../examples/magnets/dojofun/widget/templates/Magnet.html"),
	templateCssPath: dojo.uri.dojoUri("../examples/magnets/dojofun/widget/templates/Magnet.css"),

	initializer: function(){
		this.phrase = "hello world";
		this.domNodeClass="magnet";
		this.constrainTo="";
	},

	postCreate: function(){
		this.domNode.innerHTML=this.phrase;
		dojo.html.addClass(this.domNode, this.domNodeClass);
		dojo.html.disableSelection(this.domNode);

		this.drag = new dojo.dnd.HtmlDragMoveSource(this.domNode);
		this.drag.constrainTo(dojo.byId(this.constrainTo));
	},

	highlight: function(){
		this.domNode.style.borderColor = "blue";
		dojo.html.setOpacity(this.domNode, 0.5);
	},

	unhighlight: function(){
		this.domNode.style.borderColor = "black";
		dojo.html.setOpacity(this.domNode, 1.0);
	},

	moveTo: function(coords){
		var cpos = dojo.html.abs(this.domNode);
		with(this.domNode.style){
			if(position != "absolute"){
				left = cpos.x+"px";
				top = cpos.y+"px";
				position = "absolute";
			}
		}
		var anim = dojo.lfx.propertyAnimation(
			[this.domNode],
			[	{ property: "top", end: coords.y },
				{ property: "left", end: coords.x }	],
			100,
			dojo.lfx.easeInOut
		).play(20);
	}
});
