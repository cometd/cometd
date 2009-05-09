dojo.provide("dojofun.Magnet");
dojo.require("dojo.dnd.Moveable");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");

dojo.declare("dojofun.Magnet", [dijit._Widget, dijit._Templated], { 
	
	templatePath: dojo.moduleUrl("dojofun","Magnet.html"),

	url:"",

	constructor: function(){
		this.phrase = "hello world";
		this.domNodeClass="magnet";
		this.constrainTo="";
	},

	postCreate: function(){
		
		this.inherited(arguments);
		this.domNode.innerHTML=this.phrase;
		dojo.addClass(this.domNode, this.domNodeClass);
		dojo.setSelectable(this.domNode, false);

//		this.drag = new dojo.dnd.move.constrainedMoveable(this.domNode, {
		this._moveable = new dojo.dnd.Moveable(this.domNode);
//		});


	},

	highlight: function(){
		dojo.style(this.domNode,{
			borderColor: "blue",
			opacity: 0.5
		});
	},

	unhighlight: function(){
		dojo.style(this.domNode,{
			borderColor: "black",
			opacity: 1
		});
	},

	moveTo: function(coords){
//		var cpos = dojo.coords(this.domNode);
		with(this.domNode.style){
			if(position != "absolute"){
//				left = coords.x+"px";
//				top = coords.y+"px";
				position = "absolute";
			}
		}
		dojo.anim(this.domNode,{
			top: coords.y, left:coords.x
		});
//		var anim = dojo.lfx.propertyAnimation(
//			[this.domNode],
//			[	{ property: "top", end: coords.y },
//				{ property: "left", end: coords.x }	],
//			100,
//			dojo.lfx.easeInOut
//		).play(20);
	}
});
