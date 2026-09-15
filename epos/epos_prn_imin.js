function createDriver_imin(){
    var o={
        buffer:"",
        flag_cut:false,
        flag_cash:false,
        connect:function(device,success,fail)
        {
            this.connection = new IminPrinter();
            this.connection.connect().then(async (isConnect) => 
            {
                console.log(isConnect);
                if(isConnect)
                {
                    let status = await this.connection.getPrinterStatus();
                    console.log(status)
                    this.isConnected = isConnect;
                    this.connection.initPrinter();
                    if(success)
                     success(status.text);
                }
            });
        },
        setAlign:function(align)
        {
            //align=0-Izquierda, 1-Centro y 2-derecha
            if(this.isConnected) { this.connection.setAlignment(align); }
        },
        setFontBold:function(flag)
        {
            //flag=true/false
            if(this.isConnected) { this.connection.setTextStyle(flag ? 1 : 0); }
        },
        setFontItalic:function(flag)
        {
            //flag=true/false
            if(this.isConnected){ this.connection.setTextStyle(flag ? 2 : 0); }
        },
        setFontUnderline:function(flag)
        {
            //flag=true/false
        },
        setFontSize:function(size)
        {
            //size=0-Normal, 1-2x, 2-3x  ->mm
            if(this.isConnected){ this.connection.setTextSize(size); }
        },
        printText:function(txt,cut=false)
        {
            //txt=Cadena de texto a imprimir
            if(this.isConnected && txt)
			{
                this.connection.printText(txt);//si requiere salto de linea al texto agreguele  '\n'
                if(cut)this.connection.printAndFeedPaper(50);
            }
        },
        printBarcode:function(std,data)
        {
            //std={standar, width, height, type, error_correction, contentPosition=0-noPrint/1=aboveBar/2=BelowBar/3=Both, etc..}
            //data=Datos
            if(this.isConnected && std){
                this.connection.setBarCodeWidth(std.width);
                this.connection.setBarCodeHeight(std.height);
                this.connection.setBarCodeContentPrintPos(std.contentPosition);
                this.connection.printBarCode(std.type, data);
                this.connection.printAndFeedPaper(100);
            }
        },
        printQr:function(std,data)
        {
            //std={standar, size=1-9, position=0-left/1-center/2-right, error_correction,etc..}
            //data
            if(this.isConnected && std){
                //o.window.QrCodeSize = std.size;
                this.connection.setQrCodeSize(std.size);
                this.connection.setQrCodeErrorCorrectionLev(std.error_correction);
                this.connection.printQrCode(data, std.position)
                this.connection.printAndFeedPaper(100);
            }
        },
        printImage:function(source,h,w)
        {
            //source=Cadena o qué? -> dataURL
            //h=alto
            //w=ancho (en qué unidad?)
            if(this.isConnected && source){
                this.connection.printSingleBitmap(source);
                this.connection.printAndFeedPaper(200);
            }
        },
        writeHexBytes:function(hex)
        {
            //hex=Cadena delimitada por comas con secuencias hexadecimales (bytes) Ejemplo: 1B,40,C1
        },
        cut:function()
        {
            this.connection.partialCut();
        },
        openCashDrawer:function()
        {
            this.connection.openCashBox();
        }
    };
    return o;
}