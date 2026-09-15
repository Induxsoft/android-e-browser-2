function createDriver_generic(){
    var o={
        buffer:"",
        flag_cut:false,
        flag_cash:false,
        connect:function(device,success,fail)
        {
            switch(device.type)
            {
                case "ethernet":
                    dsEscPrn.openPrinterTCP(device.address, device.port, device.timeout, device.dpi, device.printWidth, device.charsPerLine);
                    break;
                case "usb":
                    dsEscPrn.openPrinterUSB(device.dpi, device.printWidth, device.charsPerLine);
                    break;
                case "bluetooth":
                    dsEscPrn.openPrinterBluetooth(device.dpi, device.printWidth, device.charsPerLine);
                    break;
            }
            if(success)success("conectado");
        },
        setAlign:function(align)
        {
            //align=0-Izquierda, 1-Centro y 2-derecha
            this.buffer += align <= 0 ? "[L]" : align >= 2 ? "[R]" : "[C]";
        },
        setFontBold:function(flag)
        {
            //flag=true/false
            if(flag) { 
                if(this.buffer.includes("$_text")){ this.buffer.replace("$_text","<b>$_text<b>"); }
                else{ this.buffer += "<b>$_text<b>"; }
            }
        },
        setFontItalic:function(flag)
        {
            //flag=true/false
            this.buffer += "*no soportado*";
        },
        setFontUnderline:function(flag)
        {
            //flag=true/false
            if(flag) { if(this.buffer.includes("$_text")){ this.buffer.replace("$_text","<u>$_text</u>"); }
                else{ this.buffer += "<u>$_text</u>"; }
            }
        },
        setFontSize:function(size)
        {
            //size = 10-normal, 20-2x, 30-3x, ...70-7x
            let s='normal';
            switch(parseInt(size))
            {
                case 10: s='normal'; break;
                case 20: s='big'; break;
                case 30: s='big-2'; break;
                case 40: s='big-3'; break;
                case 50: s='big-4'; break;
                case 60: s='big-5'; break;
                case 70: s='big-6'; break;
                default: s='normal'; break;
            }
            if(this.buffer.includes("$_text")){ this.buffer.replace("$_text",`<font size='${s}'>$_text</font>`); }
                else{ this.buffer += `<font size='${s}'>$_text</font>`; }
        },
        formatOutPrintText:async function(txt)
        {
            if(this.buffer.includes("$_text")) this.buffer = this.buffer.replace("$_text", txt) + "\n";
            else this.buffer += txt + "\n"; 
            
            const alignments = ["[L]", "[C]", "[R]"];

            if(!alignments.some(al => this.buffer.includes(al))) this.buffer = "[L]" + this.buffer;
            
            console.log(this.buffer);
        },
        printText:async function(txt)
        {
            //txt=Cadena de texto a imprimir
            await this.formatOutPrintText(txt);
            dsEscPrn.printFormattedText("[L]\n"+this.buffer);
            this.buffer="";
        },
        printBarcode:function(std,data)
        {
            // type = 0-ean13, 1-ean8, 2-upca, 3-upce, 4-128, 5-itf / height=0-n mm / width 0-n mm / contentPosition 0=noPrint,1=aboveBar,2=BelowBar,3=Both
            let t="ean13";
            switch(std.type)
            {
                case 0: t="ean13"; break;
                case 1: t="ean8"; break;
                case 2: t="upca"; break;
                case 3: t="upce"; break;
                case 4: t="128"; break;
                case 5: t="itf"; break;
                default: t="ean13"; break;
            }
            
            let p="below";
            switch(std.contentPosition)
            {
                case 0: p="none"; break;
                case 1: p="above"; break;
                case 2: p="below"; break;
                default: p="below"; break;
            }

            let barCode = `<barcode type='${t}' height='${(std.height??100)/10}' width='${(std.width??50)*10}' text='${p}'>${data}</barcode>`;
            this.printText(barCode);
        },
        printQr:function(std,data)
        {
            // size=0-n mm / position= 0-izquierda, 1-centro, 2-derecha
            let p="[L]"
            switch (std.position)
            {
                case 0: p="[L]"; break;
                case 1: p="[C]"; break;
                case 2: p="[R]"; break;
                default: p="[L]"; break;
            }
            let qrCode = `${p}<qrcode size='${std.size*5}'>${data}</qrcode>`;
            this.printText(qrCode);
        },
        printImage:function(source,h,w)
        {
            //source=Cadena o qué? -> dataURL
            //h=alto
            //w=ancho (en qué unidad?)
            if(source)this.printText(`<img></img>`);
        },
        cut:async function(txt="")
        {
            await this.formatOutPrintText(txt);
            dsEscPrn.printFormattedTextAndCut("[L]\n"+this.buffer);
            this.buffer="";
        },
        openCashDrawer:async function(txt="")
        {
            await this.formatOutPrintText(txt);
            dsEscPrn.printFormattedTextAndOpenCashBox("[L]\n"+this.buffer,0);
            this.buffer="";
        }
    };
    return o;
}

