package com.geniatech.nfsscaner;

import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.ethernet.EthernetDevInfo;
import android.net.ethernet.EthernetManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.app.Activity;
import android.app.AlertDialog.Builder;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class MainActivity extends Activity implements View.OnClickListener{
    public final static boolean DEBUG = false;
    public final static String TAG = "NFS_SCAN_MainActivity";
    public final static String PRIVATE_PATH = "/data/data/com.geniatech.nfsscaner/";
    public final static String SCAN_IP_EXEC = PRIVATE_PATH + "busybox ping -w 1 -c 1 ";
    public final static String SCAN_NFS_EXEC = PRIVATE_PATH + "showmount -e ";
    public final static String MOUNT_NFS_EXEC = PRIVATE_PATH + "busybox mount -o nolock -t nfs ";
    public final static String SDCARD_PATH = "/mnt/sdcard/";
    
    public final static int MSG_IP_SCAN_DONE = 0;
    public final static int MSG_NFS_SCAN_DONE = 1;
    public final static int MSG_MOUNT_DONE = 2;
    
    public final static int GROUP_NUM = 10;
    public static Integer GroupStae=0;
    private Button mBtnScan;
    private Button mBtnOpen;
    private Button mBtnExit;
    private ListView mListView;
    private Handler mHandler;
    private List<String> mSubnetIps;
    private List<String> mActHostIps;
    private List<String> mNfsDirs;

    private ProgressDialog mProgressDialog = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        copyUtils();
        mHandler = new ScanHandler();
        mSubnetIps = new ArrayList<String>();
        mActHostIps = new ArrayList<String>();
        mNfsDirs = new ArrayList<String>();
    }

    private void initViews(){
        mBtnScan = (Button)findViewById(R.id.button1);
        mBtnScan.setText(R.string.scan);
        mBtnOpen = (Button)findViewById(R.id.button2);
        mBtnExit = (Button)findViewById(R.id.button3);
        mBtnScan.setOnClickListener(this);
        mBtnOpen.setOnClickListener(this);
        mBtnExit.setOnClickListener(this);
        
        mListView = (ListView)findViewById(R.id.listView1);
        mListView.setOnItemClickListener(listItemListener);
    }
    
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.button1:
                startProgressDialog("","Scanning NFS directories...");
                //if(!isScaning) startScan();
                getSubnetIps();
                getActiveHostMultiThreads();
                break;
            case R.id.button2:
                openFileBrowser();
                break;
            case R.id.button3:
                finish();
                break;
        }
    }
    private OnItemClickListener listItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
            showMountDialog(mNfsDirs.get(arg2));
        }
    };
    private void openFileBrowser(){
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.rhmsoft.fm", "com.rhmsoft.fm.FileManager"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    
    private void getSubnetIps(){
        mSubnetIps.clear();
        calculateSubnetIps();
        for(int i=2;i<254;i++){
            String subip = "192.168.1."+i;
            //mSubnetIps.add(subip);
        }
    }
    
    private void getActiveHostMultiThreads(){
        int subipSum = mSubnetIps.size();
        int groupSum = subipSum/GROUP_NUM;
        mActHostIps.clear();
        GroupStae = groupSum;
        for(int i=0;i<=groupSum;i++){
            new IpScanThread(i).start();
        }
    }
    private void getNfsDirs(){
        Log.i(TAG,"---------------------------size of active host:"+mActHostIps.size());
        mNfsDirs.clear();
        new Thread(){
            public void run(){
                for(String subnet:mActHostIps){
                    String cmd = SCAN_NFS_EXEC + subnet;
                    Vector<String> dirs = execRootCmd(cmd);
                    if(dirs != null){
                        String[] strs = dirs.get(0).split("\n");
                        for(String dir:strs){
                            Log.i(TAG,subnet+":nfsdirs===>"+dir);
                            if(dir.length() > "0.0.0.0".length())
                                mNfsDirs.add(dir);
                        }
                    }
                }
                mHandler.sendEmptyMessage(MSG_NFS_SCAN_DONE);
            }
        }.start();
    }
    public class ScanHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch(msg.what){
                case MSG_IP_SCAN_DONE:
                    Log.i(TAG,"-----------------ipip-----------ip scan completed.");
                    getNfsDirs();
                    break;
                case MSG_NFS_SCAN_DONE:
                    Log.i(TAG,"----------------------------nfs scan completed.");
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this,
                            android.R.layout.simple_list_item_1, mNfsDirs);
                    mListView.setAdapter(adapter);
                    stopProgressDialog();
                    mBtnScan.setText(R.string.recan);
                    break;
                case MSG_MOUNT_DONE:
                    break;
            }
        }
    }
    public class IpScanThread extends Thread{
        private int groupIndex;
        IpScanThread(int index){
            groupIndex = index;
        }
        public void run(){
            int head = groupIndex*GROUP_NUM;
            int tail = head + GROUP_NUM;
            if(tail>mSubnetIps.size()) tail = mSubnetIps.size();
            for(int i = head;i<tail;i++){
                String subnet = mSubnetIps.get(i);
                String cmd = SCAN_IP_EXEC + subnet;
                Vector<String> result = execRootCmd(cmd);
                String res = null;
                if(result==null){
                    continue;
                }else{
                    res = result.get(0);
                }
                if(DEBUG) Log.i(TAG,"+++++++++++++++++++++active :"+subnet+"     "+res);
                if(res.equals("true\n")){
                    Log.i(TAG,"----------------------------active :"+subnet+":"+res);
                    synchronized (mActHostIps) {
                        mActHostIps.add(subnet);
                    }
                }
            }
            synchronized (GroupStae) {
                GroupStae--;
                if(GroupStae < 0){
                    mHandler.sendEmptyMessage(MSG_IP_SCAN_DONE);
                }
            }
        }
    }
    protected static Vector<String> execRootCmd(String paramString){
        Vector<String> localVector = new Vector<String>();
        if(DEBUG) Log.i(TAG, "input = " + paramString);
        DataInputStream dis = null;
        Runtime r = Runtime.getRuntime();
        try{
            Process p = r.exec("/system/xbin/su");
            final OutputStreamWriter out = new OutputStreamWriter(p.getOutputStream());
            out.write(paramString); 
            out.write("\n");  
            out.flush(); 
            out.write("exit\n");  
            out.flush();
            out.close();
            
            InputStream input = p.getInputStream();
            dis = new DataInputStream(input);

            String content = null;
            StringBuilder sb = new StringBuilder();
            while ((content = dis.readLine()) != null){
                sb.append(content).append("\n");
            }
            if(DEBUG) Log.i(TAG, "output = " + sb.toString());
            localVector.add(sb.toString());
        }catch (IOException e){
            e.printStackTrace();
            localVector = null;
        }finally{
            if (dis != null){
                try{
                    dis.close();
                }catch (IOException e){
                    e.printStackTrace();
                    localVector = null;
                }
            }
        }
        return localVector;
    }
    private void startProgressDialog(String title,String msg){
        stopProgressDialog();
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle(title);
        mProgressDialog.setMessage(msg);
        mProgressDialog.setCancelable(true);
        mProgressDialog.show();
    }
    
    private void stopProgressDialog(){
        if(mProgressDialog != null){
            mProgressDialog.cancel();
            mProgressDialog = null;
        }
    }
    private void showMountDialog(String path){
        final String nfsDir = path;
        Builder bld = new Builder(this);
        //bld.setTitle("Mount NFS directory");
        String msg = getString(R.string.mount_msg, nfsDir,"/mnt/sdcard/");
        bld.setMessage(msg);
        bld.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mountDirectory(nfsDir);
            }
        });
        bld.setNegativeButton(android.R.string.cancel, null);
        bld.create().show();
    }
    private void mountDirectory(String dir){
        String ip = dir.substring(0, dir.lastIndexOf(":"));
        Log.i("=======================","dir:"+ip);
        String ipdir = SDCARD_PATH+ip;
        File file = new File(ipdir);
        if(file.exists()){
            //execRootCmd("umount " + file.getAbsolutePath());
            //execRootCmd(MOUNT_NFS_EXEC + file.getAbsolutePath());
        }else{
            execRootCmd("mkdir " + file.getAbsolutePath());
        }
        String dirName = dir.substring(dir.lastIndexOf(":")+1);
        dirName = dirName.replace("/", "_");
        file = new File(ipdir + "/" + dirName);
        if(file.exists()){
            execRootCmd("umount " + file.getAbsolutePath());
        }else{
            execRootCmd("mkdir " + file.getAbsolutePath());
        }
        execRootCmd(MOUNT_NFS_EXEC + dir + " " + file.getAbsolutePath());
        Log.i("-------",MOUNT_NFS_EXEC + dir + " " + file.getAbsolutePath());
    }
    private void copyUtils(){
        try{
            File file = new File(PRIVATE_PATH+"busybox");
            if(!file.exists()){
                InputStream is = getAssets().open("busybox");
                file.createNewFile();
                FileOutputStream os = new FileOutputStream(file);
                byte[] buff = new byte[1024*4];
                int read_len;
                while((read_len = is.read(buff))!=-1){
                    os.write(buff, 0, read_len);
                }
                os.close();
                is.close();
                execRootCmd("chmod 700 "+file.getAbsolutePath());
            }
            
            file = new File(PRIVATE_PATH+"showmount");
            if(!file.exists()){
                InputStream is = getAssets().open("showmount");
                file.createNewFile();
                FileOutputStream os = new FileOutputStream(file);
                byte[] buff = new byte[1024*4];
                int read_len;
                while((read_len = is.read(buff))!=-1){
                    os.write(buff, 0, read_len);
                }
                os.close();
                is.close();
                execRootCmd("chmod 700 "+file.getAbsolutePath());
            }
            
        }catch(Exception e){
            Log.e(TAG,"open file erro!");
        }
    }
    private void calculateSubnetIps(){
        int ipAddress = -1;
        int netMask = -1;
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if(ni.getType()==ConnectivityManager.TYPE_WIFI){
            WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
            DhcpInfo  di = wm.getDhcpInfo();
            netMask = di.netmask;
            ipAddress = di.ipAddress;
            if(DEBUG) Log.i("~~~~~~~3333~~~~~~~","~~~~~~~~~~~~wifi~~~~~ip:"+Integer.toHexString(ipAddress)
            		+"~~~~~~~~mask~~:"+Integer.toHexString(netMask));
        }else if(ni.getType()==ConnectivityManager.TYPE_ETHERNET){
            EthernetManager em = (EthernetManager)getSystemService(Context.ETH_SERVICE);
            EthernetDevInfo edi = em.getSavedEthConfig();
            if(edi.getConnectMode().equals(EthernetDevInfo.ETH_CONN_MODE_DHCP)){
                DhcpInfo di = em.getDhcpInfo();
                ipAddress = di.ipAddress;
                netMask = di.netmask;
                if(DEBUG) Log.i("~~~~~~~22222~~~~~~~","~~~~~eth dynamic~~ip:"+Integer.toHexString(ipAddress)
                		+"~~~~~~~~mask~~:"+Integer.toHexString(netMask));
            }else{// manual mode
                ipAddress = NetworkUtils.inetAddressToInt(NetworkUtils.numericToInetAddress(edi.getIpAddress()));
                netMask = NetworkUtils.inetAddressToInt(NetworkUtils.numericToInetAddress(edi.getNetMask()));
                if(DEBUG) Log.i("~~~~~~~1111~~~~~~~~","~~~~~~eth static~~~~ip:"+Integer.toHexString(ipAddress)
                		+"~~~~~mask~~:"+Integer.toHexString(netMask));
            }
        }
        
        int ips = changeEndian(netMask);
        ips = (~ips) & 0xFFFFFFFF;
        int ipBase = (changeEndian(netMask) & changeEndian(ipAddress));
        for(int i=2;i<ips;i++){
	        InetAddress inet = NetworkUtils.intToInetAddress(changeEndian(ipBase+i));
	        mSubnetIps.add(inet.getHostAddress());
	        Log.i("------","-------:"+inet.getHostAddress());
        }
    }
    
    public static int changeEndian(int s){
    	int dest=0;
    	dest = ((s & 0x00ff00ff)<<8)+((s & 0xff00ff00)>>>8);
    	dest = ((dest & 0x0000ffff)<<16)+((dest & 0xffff0000)>>>16);
    	return dest;
    }
    private void getSubIps(String ip,String netmask){
        
    }
}




