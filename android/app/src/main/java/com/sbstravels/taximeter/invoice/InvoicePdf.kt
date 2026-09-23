package com.sbstravels.taximeter.invoice

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import org.json.JSONObject

object InvoicePdf {
 fun create(context:Context,invoice:JSONObject):File{
  val d=PdfDocument();val page=d.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val p=Paint().apply{textSize=14f}
  var y=50f
  fun line(s:String,size:Float=14f){p.textSize=size;page.canvas.drawText(s,40f,y,p);y+=size+12f}
  line("SBS TRAVELS",24f);line("Taxi Invoice",18f);line("Invoice: "+invoice.optString("invoice_number","—"))
  y+=12f
  val b=invoice.optJSONObject("breakdown")?:JSONObject()
  line("Fare Summary",18f)
  line("Base fare: Rs. %.2f".format(b.optDouble("base_fare",0.0)))
  line("Distance: %.2f km".format(b.optDouble("distance_km",0.0)))
  line("Distance fare: Rs. %.2f".format(b.optDouble("distance_fare",0.0)))
  line("Waiting: %.1f min".format(b.optDouble("waiting_minutes",0.0)))
  line("Waiting fare: Rs. %.2f".format(b.optDouble("waiting_minutes",0.0)*b.optDouble("waiting_per_minute",0.0)))
  line("Extras: Rs. %.2f".format(b.optDouble("extra_fare",0.0)))
  y+=10f;line("TOTAL: Rs. %.2f".format(invoice.optDouble("total",0.0)),22f)
  line("Thank you for travelling with SBS Travels.")
  d.finishPage(page)
  val dir=File(context.cacheDir,"invoices").apply{mkdirs()}
  val file=File(dir,invoice.optString("invoice_number","invoice")+".pdf");file.outputStream().use{d.writeTo(it)};d.close();return file
 }
}