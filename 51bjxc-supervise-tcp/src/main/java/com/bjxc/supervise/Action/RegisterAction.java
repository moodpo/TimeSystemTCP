package com.bjxc.supervise.Action;


import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.bjxc.supervise.netty.Action;
import com.bjxc.supervise.netty.Header;
import com.bjxc.supervise.netty.HexUtils;
import com.bjxc.Result;
import com.bjxc.model.DevAssign;
import com.bjxc.supervise.http.crypto.HttpClientService;
import com.bjxc.supervise.http.crypto.JSPTConstants;
import com.bjxc.supervise.model.Device;
import com.bjxc.supervise.model.LoginInfo;
import com.bjxc.supervise.model.OperationLog;
import com.bjxc.supervise.model.TrainingCar;
import com.bjxc.supervise.netty.Message;
import com.bjxc.supervise.netty.MessageUtils;
import com.bjxc.supervise.netty.server.TCPMap;
import com.bjxc.supervise.service.DevAssignService;
import com.bjxc.supervise.service.DeviceService;
import com.bjxc.supervise.service.OperationLogService;
import com.bjxc.supervise.service.TrainingCarService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.Timeout;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 终端注册
 * @author cras
 * ID:0x0100
 */
public class RegisterAction implements Action{
	private static final Logger logger = LoggerFactory.getLogger(RegisterAction.class);
	
	@Resource
	private DeviceService deviceService;
	@Resource
	private DevAssignService devAssignService;
	@Resource
	private HttpClientService httpClientService;
	@Resource
	private TrainingCarService trainingCarService;
	@Resource
	private OperationLogService operationLogService;
	
	@Value("${bjxc.jspt.http.url}")
	private String jsptHttpUrl;
	
	@Value("${bjxc.jspt.platformNo}")
	private String platformNo;
	
	public void action(ActionContext actionContext) {
		
		//String socketString = actionContext.getChannelHandlerContext().channel().remoteAddress().toString();  
		//logger.info(socketString);
		logger.info("0x0100：终端注册");
		Message msg = actionContext.getMessag();
		
	
		byte[] body = msg.getBody();
		ByteBuf hbuf = Unpooled.copiedBuffer(body);
		
		//标示终端安装车辆所在的省域，0保留，由平台取默认值。省域ID采用GB/T 2260中规定的行政区划代码6位中前2位
		short proId = hbuf.readShort();
		logger.info("省域ID:"+proId);

		//标示终端安装车辆所在的市域和县域，0保留，由平台取默认值。市县域ID采用GB/T 2260中规定的行政区划代码6位中后4位
		short cId=  hbuf.readShort();
		logger.info("县域ID:"+cId);

		//5个字节，终端制造商编码
		byte[] cbyt = new byte[5];
		hbuf.readBytes(cbyt);
		String creater=null;
		try {
			creater = new String(cbyt,"GBK");
			logger.info("creater:"+creater);
		} catch (UnsupportedEncodingException e) {
			logger.info("creater读取错误"+e);
		}
		
		
		//20个字节，此终端型号由制造商自行定义，位数不足20位的，后补“0X00”
		byte[] typeBytes = new byte[20];
		hbuf.readBytes(typeBytes);
		String type=null;
		try {
			type = new String(typeBytes,"GBK").trim();
			logger.info("type:"+type);
		} catch (UnsupportedEncodingException e) {
			logger.info("type读取错误"+e);
		}
		
		
		//7个字节，由大写字母和数字组成，此终端ID由制造商自行定义，位数不足时，后补“0X00”
		byte[] serialBytes = new byte[7];
		hbuf.readBytes(serialBytes);
		String serialNo=null;
		try {
			serialNo = new String(serialBytes,"GBK").trim();
			logger.info("serialNo:"+serialNo);
		} catch (UnsupportedEncodingException e) {
			logger.info("serialNo读取错误"+e);
		}

		//国际移动设备标识，ASCII码，15个字节
		byte[] IMEIBytes = new byte[15];
		hbuf.readBytes(IMEIBytes);
		String imei=null;
		try {
			imei = new String(IMEIBytes,"GBK");
			logger.info("IMEI:"+imei);
		} catch (UnsupportedEncodingException e) {
			logger.info("IMEI读取错误"+e);
		}
	
		//车牌颜色，按照JT/T415-2006的5.4.12；
		//未上牌时，取值为0
		byte colorByte = hbuf.readByte();
		String color = String.valueOf((int)colorByte);
		logger.info("Color:"+color);

		
		//剩下的字节为车辆标识，车牌颜色为0时，表示车辆VIN；
		//否则，表示公安交通管理部门颁发的机动车号牌
		byte[] req = new byte[hbuf.readableBytes()];
		hbuf.readBytes(req);
		String licNum=null;
		try {
			licNum = new String(req,"GBK");
			logger.info("车辆标识："+licNum);
		} catch (UnsupportedEncodingException e) {
			logger.info("车辆标识读取错误"+e);
		}
		
		//添加到消息通道
		String socketString = actionContext.getChannelHandlerContext().channel().remoteAddress().toString();  
		TCPMap.getInstance().getChannelMap().put(msg.getHeader().getMobile(), actionContext.getChannelHandlerContext());
		TCPMap.getInstance().getIpMap().put(msg.getHeader().getMobile(), socketString);
		
		//回复信息
		Message result = new Message();
		Header header = new Header();
		header.setId((short)0x8100);
		//header.setMobile(msg.getHeader().getMobile() );
		header.setMobile(JSPTConstants.JSPT_PLATFORMNO_BCD);
		header.setNumber(MessageUtils.getCurrentMessageNumber());
		result.setHeader(header);
		
		//如果教练车是数据库中不存在2，车辆已被注册1情况，则无法注册，不返回后面内容
		//如果终端是数据库中不存在4，终端已被注册3，则无法注册不返回后面内容
		ByteBuf restbuf = Unpooled.buffer();
		Integer results=deviceService.searchDevice(licNum, imei,msg.getHeader().getMobile());
		logger.info("查询结果：results= "+results);
		
		restbuf.writeShort(msg.getHeader().getNumber());
		restbuf.writeByte(results.byteValue());

		Device device =  null;
		TrainingCar trainingCar = null;
		
		
		if(results==0){
			device = deviceService.getDeviceByImei(imei);
			com.bjxc.model.TrainingCar trainingCarByLicnum = trainingCarService.getTrainingCarByLicnum(licNum);
			device.setCarId(trainingCarByLicnum.getId());
			device.setLicnum(licNum);
			try{
				//String platformNo =  "A0041";
				restbuf.writeBytes(platformNo.getBytes("GBK"));
				restbuf.writeBytes(device.getInscode().getBytes("GBK"));
				restbuf.writeBytes(device.getDevnum().getBytes("GBK"));
				String passwd = device.getPasswd();
				for (int i = passwd.length(); i < 12; i++) {
					passwd += "\000";	//证书口令不足12位补空字节
				}
				restbuf.writeBytes(passwd.getBytes("GBK"));
				restbuf.writeBytes(device.getKey().getBytes("GBK"));
				
				//登录信息添加
				LoginInfo loginInfo = new LoginInfo();
				Device loginInfoDevice = new Device();
				loginInfoDevice.setId(device.getId());
				loginInfoDevice.setDevnum(device.getDevnum());
				trainingCar = new TrainingCar();
				trainingCar.setTrainingCarId(device.getCarId());
				trainingCar.setTrainingLicNum(device.getLicnum());
				trainingCar.setTrainingCarNum(trainingCarByLicnum.getCarnum());
				loginInfo.setTrainingCar(trainingCar);
				loginInfo.setDevice(loginInfoDevice);
				loginInfo.setMapMode(device.getMapType());
				
				//保存注册登录信息
				logger.info("保存loginInfo: "+msg.getHeader().getMobile());
				TCPMap.getInstance().getLoginInfoMap().put(msg.getHeader().getMobile(),loginInfo);
				
				//TODO:临时
//				DevAssign devAssign = new DevAssign();               
//				devAssign.setDeviceId(device.getId());
//				devAssign.setTrainingcarId(trainingCar.getTrainingCarId());
//				devAssign.setSim( msg.getHeader().getMobile());
//				devAssignService.add(devAssign);
			}catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		byte[] respondBody = new byte[restbuf.readableBytes()];
		restbuf.readBytes(respondBody);
		result.setBody(respondBody);
		if(result.getBody().length + Header.maxLength + 100 >= Message.maxLength){
			List<Message> dividePackage = MessageUtils.dividePackage(result);
			int length = dividePackage.size();
			Timer timer = new Timer();
			TimerTask timerTask = new TimerTask() {
				private int i = 0;
				public void run(){
					actionContext.writeAndFlush(dividePackage.get(i++));
					if(i >= length){
						timer.cancel();
					}
				}
			};
			timer.schedule(timerTask,50,50);
			/*for(Message message: MessageUtils.dividePackage(result)){
				actionContext.writeAndFlush(message);
			}*/
		}else	{
			actionContext.writeAndFlush(result);
		}
		
		//TODO:临时注释 
		//向省平台备案计时终端
		if(results==0){
			
			LoginInfo loginInfo = TCPMap.getInstance().getLoginInfoMap().get(msg.getHeader().getMobile());
			device = loginInfo.getDevice();
			trainingCar = loginInfo.getTrainingCar();
			
			
			//向省平台绑定设备及教练车关系
			HashMap<String,String> params = new HashMap<String,String>();
			params.put("devnum", device.getDevnum());
			params.put("carnum", trainingCar.getTrainingCarNum());
			params.put("sim", msg.getHeader().getMobile().substring(5));
			try {
				Result result2 = httpClientService.doPost(jsptHttpUrl+"/province/devassign", params);
				if(result2.getErrorCode()==0){
					logger.info("http绑定成功");
					//省平台通知成功了，再修改我们的数据库，保证数据一致性。数据库绑定设备跟教练车
					DevAssign devAssign = new DevAssign();               
					devAssign.setDeviceId(device.getId());
					devAssign.setTrainingcarId(trainingCar.getTrainingCarId());
					devAssign.setSim( msg.getHeader().getMobile());
					devAssignService.add(devAssign);
					//添加日志记录
					OperationLog operationLog = new OperationLog();
					operationLog.setInsId(2);
					operationLog.setLogEvent("TCP终端注册："+device.getDevnum());
					operationLog.setLogTime(new Date());
					operationLog.setLogUser("终端");
					operationLog.setRemark("");
					operationLogService.add(operationLog);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
