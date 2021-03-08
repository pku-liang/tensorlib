package systolic

import chisel3._
import chisel3.util._
//import chisel3.Driver
import chisel3.iotesters.{PeekPokeTester, Driver}
import java.io.PrintWriter
import scala.math._
class MemBankAutoAddr(dep: Int, width: Int)extends Module{
  def log2(k: Int): Int = (log10(k-1)/log10(2)).toInt+1
  val addrwidth=log2(dep)
  val rd_addr = RegInit(0.U(addrwidth.W))
  val wr_addr = RegInit((dep/2).U(addrwidth.W))
  val io = IO(new Bundle{
    val wr_data = Input(Valid(UInt(width.W)))
    //val in_addr = Input(UInt(addrwidth.W))
    //val out_addr = Input(Valid(UInt(addrwidth.W)))
    val rd_data = Output(Valid(UInt(width.W)))
    val rd_valid = Input(Bool())
  })
  val mem = SyncReadMem(dep, UInt(width.W))
  val read_dt = RegInit(0.U(width.W))
  when(io.wr_data.valid){
    mem.write(wr_addr, io.wr_data.bits)
    wr_addr := Mux(wr_addr + 1.U === dep.asUInt, 0.U, wr_addr + 1.U)
  }
  //read_dt := mem.read(io.out_addr)
  io.rd_data.valid := RegNext(io.rd_valid, false.B)
  io.rd_data.bits := mem.read(rd_addr)
  rd_addr := Mux(rd_addr + Mux(io.rd_valid, 1.U, 0.U) === dep.asUInt, 0.U, rd_addr + Mux(io.rd_valid, 1.U, 0.U))
}
class PEArrayMem(pe_h: Int, pe_w: Int, vec: Array[Int], width: Int, stt: Array[Array[Int]], io_type: Array[Boolean], num_op : Int, float: Boolean = false) extends Module{
  val dataflows = for(i <- 0 until num_op) yield{
    val xy_diff = stt(i)(0)!=0||stt(i)(1)!=0
    val t_diff = stt(i)(2)!=0
    //var res = TensorDataflow
    if(!xy_diff && t_diff){
      StationaryDataflow
    }else if(xy_diff && t_diff){
      SystolicDataflow
    }else{
      DirectDataflow
    }
  }
  // pe definition
  val pes = for(i <- 0 until pe_h) yield{
    for(j <- 0 until pe_w) yield{
      Module(new PE(vec, width, dataflows.toArray, io_type, num_op, float)).io
    }
  }
  var num_io_banks = Array.fill(num_op)(0)
  // pe connection, calculate bank number
  for(i <- 0 until num_op){
    val dirx = if(dataflows(i)==StationaryDataflow) 1 else stt(i)(0)
    val diry = if(dataflows(i)==StationaryDataflow) 0 else stt(i)(1)
    // connection ports
    if(io_type(i) ||(dataflows(i)!=DirectDataflow)){   // direct output use reduction tree
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          if(j+diry < pe_h && j+diry >=0 && k+dirx < pe_w && k+dirx >= 0){
            pes(j)(k).data(i).out <> pes(j+diry)(k+dirx).data(i).in
          }
        }
      }
    }
    
    // input ports
    if(io_type(i)){
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          if((j-diry >= pe_h || j-diry < 0 || k-dirx >= pe_w || k-dirx < 0)||(dirx == 0 && diry == 0)){
            num_io_banks(i) = num_io_banks(i) + 1
          }
        }
      }
    }else{
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          if((j+diry >= pe_h || j+diry < 0 || k+dirx >= pe_w || k+dirx < 0)||(dirx == 0 && diry == 0)){
            num_io_banks(i) = num_io_banks(i) + 1
          }
        }
      }
    }
  }
  val io = IO(new Bundle {
    val data = new HeterogeneousBag(
      for(i <- 0 until num_op) yield{
        if(io_type(i)) 
          Input(Vec(num_io_banks(i), Valid(UInt((vec(i) * width).W))))
        else
          Output(Vec(num_io_banks(i), Valid(UInt((vec(i) * width).W))))
    })
    val work = Input(Bool())
    val stage_cycle = Input(UInt(10.W))
  })
  val mem = for(i <- 0 until num_op) yield{
    for(j <- 0 until num_io_banks(i)) yield{
      Module(new MemBankAutoAddr(8192, vec(i) * width)).io
    }
  }
  for(i <- 0 until num_op){
    for(j <- 0 until num_io_banks(i)){
      mem(i)(j).rd_valid := io.work
      if(io_type(i)){
        mem(i)(j).wr_data := io.data(i)(j)
      }
      else{
        io.data(i)(j) := mem(i)(j).rd_data
      }
    }
  }
  val cur_cycle = RegInit(0.U(10.W))
  when(io.work){
    cur_cycle := Mux(cur_cycle + 1.U === io.stage_cycle, 0.U, cur_cycle + 1.U)
  }
  for(i <- 0 until num_op){
    val dirx = if(dataflows(i)==StationaryDataflow) 1 else stt(i)(0)
    val diry = if(dataflows(i)==StationaryDataflow) 0 else stt(i)(1)
    var bank_id = 0
    // input ports
    if(io_type(i)){
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          if((j-diry >= pe_h || j-diry < 0 || k-dirx >= pe_w || k-dirx < 0)||(dirx == 0 && diry == 0)){
            pes(j)(k).data(i).in := mem(i)(bank_id).rd_data
            bank_id = bank_id + 1
          }
        }
      }
    }else if(dataflows(i)!=DirectDataflow){
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          if((j+diry >= pe_h || j+diry < 0 || k+dirx >= pe_w || k+dirx < 0)||(dirx == 0 && diry == 0)){
            mem(i)(bank_id).wr_data := pes(j)(k).data(i).out
            bank_id = bank_id + 1
          }
          if((j-diry >= pe_h || j-diry < 0 || k-dirx >= pe_w || k-dirx < 0)||(dirx == 0 && diry == 0)){
            pes(j)(k).data(i).in.bits := 0.U
            pes(j)(k).data(i).in.valid := true.B
          }
        }
      }
    }else{
      // reduction tree
      import scala.collection.mutable.Set
      import scala.collection.mutable.ListBuffer
      val all_trees = Set[List[(Int, Int)]]();
      for(j <- 0 until pe_h){
        for(k <- 0 until pe_w){
          // no input
          pes(j)(k).data(i).in.valid := false.B
          pes(j)(k).data(i).in.bits := 0.U
          var in_set = false
          all_trees.foreach(x=>{
            if (x contains((j, k))){
              in_set = true
            }
          })
          if(!in_set){
            var lb = new ListBuffer[(Int, Int)]()
            var (dj ,dk) = (j, k)
            while(dj < pe_h && dj >= 0 && dk < pe_w && dk >= 0){
              lb += ((dj, dk))
              dj = dj + diry
              dk = dk + dirx
              //(dj, dk) = (j+diry,  k+dirx)
            }
            all_trees += lb.toList
          }
        }
      }
      var out_id = 0
      all_trees.foreach(x =>{
        val tree = Module(new AddTree(x.length, vec(2) * width)).io
        for(q <- 0 until x.length){
          tree.in.bits(q) := pes(x(q)._1)(x(q)._2).data(i).out.bits
        }
        tree.in.valid := VecInit(x.map(it=>pes(it._1)(it._2).data(i).out.valid)).reduce(_ && _)
        mem(i)(out_id).wr_data := tree.out
        out_id = out_id + 1
      })
    }
  }
  for(i <- 0 until num_op){
    for(j <- 0 until pe_h){
      for(k <- 0 until pe_w){
        if(dataflows(i)==StationaryDataflow){
          pes(j)(k).data(i).sig_in2trans.get := (cur_cycle >0.asUInt && cur_cycle <= k.asUInt)
          pes(j)(k).data(i).sig_stat2trans.get := (cur_cycle === 0.asUInt)
        }
      }
    }
  }
}

// class PEArrayMem2D(pe_h: Int, pe_w: Int, vec: Array[Int], width: Int, stt: Array[Array[Int]], io_type: Array[Boolean], num_op : Int, float: Boolean = false) extends Module{
//   val pearray = new PEArray2D(pe_h: Int, pe_w: Int, vec: Array[Int], width: Int, stt: Array[Array[Int]], io_type: Array[Boolean], num_op : Int, float: Boolean)

// }