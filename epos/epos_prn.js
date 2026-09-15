const eposprn={

    driver:null,
    connection:null,
    isConnected:null,

    add:function(deviceinfo)
    {

    },
    remove:function(deviename)
    {

    },
    connect:function(device,success,fail)
    {
        try
        {
            this.driver=eval("createDriver_"+device.driver+"()");
        }
        catch(e)
        {
            if(fail)
                fail(e.message);
            else
                throw e;
                
            return;
        }

        this.driver.connect(device,success,fail);
    },
    setAlign:function(align)
    {
        //align=0-Izquierda, 1-Centro y 2-derecha
        this.driver.setAlign(align);
    },
    setFontBold:function(flag)
    {
        //flag=true/false
        this.driver.setFontBold(flag);
    },
    setFontItalic:function(flag)
    {
        //flag=true/false
        this.driver.setFontItalic(flag);
    },
    setFontUnderline:function(flag)
    {
        //flag=true/false
        this.driver.setFontUnderline(flag);
    },
    setFontSize:function(size)
    {
        //size=0-Normal, 1-2x, 2-3x
        this.driver.setFontSize(size);
    },
    printText:function(txt)
    {
        //txt=Cadena de texto a imprimir
        this.driver.printText(txt);
    },
    printBarcode:function(std,data)
    {
        //std={standar,width,height, error_correction,etc..}
        //data=Datos
        this.driver.printBarcode(std,data);
    },
    printQr:function(std,data)
    {
        //std={standar,width,height, error_correction,etc..}
        //data
        this.driver.printQr(std,data);
    },
    printImage:function(source,h,w)
    {
        //source=Cadena o qué?
        //h=alto
        //w=ancho (en qué unidad?)
        this.driver.printImage(source,h,w);
    },
    writeHexBytes:function(hex)
    {
        //hex=Cadena delimitada por comas con secuencias hexadecimales (bytes) Ejemplo: 1B,40,C1
        this.driver.writeHexBytes(hex);
    },
    cut:function()
    {
        this.driver.cut();
    },
    openCashDrawer:function()
    {
        this.driver.openCashDrawer();
    }
};