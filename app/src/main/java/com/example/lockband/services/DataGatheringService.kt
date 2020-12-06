package com.example.lockband.services

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import com.example.lockband.MainActivity
import com.example.lockband.R
import com.example.lockband.data.DataGatheringServiceActions
import com.example.lockband.data.LockingServiceActions
import com.example.lockband.data.room.*
import com.example.lockband.utils.*
import com.khmelenko.lab.miband.MiBand
import com.khmelenko.lab.miband.listeners.HeartRateNotifyListener
import com.khmelenko.lab.miband.listeners.RealtimeStepsNotifyListener
import com.khmelenko.lab.miband.model.VibrationMode
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class DataGatheringService : Service(), SensorEventListener {

    @Inject
    lateinit var stepRepository: StepRepository

    @Inject
    lateinit var heartRateRepository: HeartRateRepository

    @Inject
    lateinit var sensorDataRepository: SensorDataRepository

    private var miBand = MiBand(this)
    private val disposables = CompositeDisposable()

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    private val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleBatteryUpdate()
        }
    }

    val alertReceiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            handleAlert()
        }
    }

    val batteryIntentFilter = IntentFilter(DataGatheringServiceActions.BATTERY.name)
    val alertIntentFilter =  IntentFilter(DataGatheringServiceActions.ALERT.name)


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                DataGatheringServiceActions.PAIR.name -> handlePairing(intent)
                DataGatheringServiceActions.START.name -> {
                    actionConnect(intent)
                    startService()
                }
                DataGatheringServiceActions.STOP.name -> stopService()
                else -> Timber.e("This should never happen. No action in the received intent")
            }
        } else {
            Timber.d(
                "with a null intent. It has been probably restarted by the system."
            )
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        // restarted if the system kills the service
        return START_STICKY
    }


    override fun onCreate() {
        super.onCreate()
        Timber.d("The Mi Band communication service has been created")
        val notification = createNotification()
        startForeground(2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("The Mi Band communication service has been destroyed")
        Toast.makeText(this, "MiBandService destroyed", Toast.LENGTH_SHORT).show()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent =
            Intent(applicationContext, DataGatheringService::class.java).also {
                it.setPackage(packageName)
            }
        val restartServicePendingIntent: PendingIntent =
            PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT)

        applicationContext.getSystemService(Context.ALARM_SERVICE)

        val alarmService: AlarmManager =
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    //Intent action handlers

    private fun startService() {
        if (isServiceStarted) return
        Timber.d("Starting the foreground service task")
        Toast.makeText(this, "Communication with band started", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setMiBandServiceState(this, MiBandServiceState.STARTED)

        // lock to avoid being affected by Doze Mode
        wakeLock =
            (getSystemService(POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiBandService::lock").apply {
                    acquire()
                }
            }

        //set up listeners for band data scans
        actionSetHeartRateNotifyListener()
        actionSetRealtimeNotifyListener()
        actionSetSensorDataNotifyListener()

        //enable realtime notify on steps and accelerometer
        actionEnableRealtimeStepsNotify()
        actionEnableSensorDataNotify()


        //Register BroadcastReceivers for battery update and vibrating alerts
        registerReceiver(batteryReceiver,batteryIntentFilter)
        registerReceiver(alertReceiver,alertIntentFilter)

        //Periodically scan heart rate
        GlobalScope.launch(Dispatchers.IO) {
            while (isServiceStarted) {
                actionStartHeartRateScan()
                delay(HR_TIMEOUT)
            }
        }


    }

    private fun stopService() {
        Timber.d("Stopping the mi band foreground service")
        Toast.makeText(this, "Mi Band Service stopping", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            actionDisableRealtimeStepsNotify()
            actionDisableSensorDataNotify()

            unregisterReceiver(batteryReceiver)
            unregisterReceiver(alertReceiver)

            disposables.clear()

            sensorManager.unregisterListener(this)

            stopForeground(true)
            stopSelf()

        } catch (e: Exception) {
            Timber.d("Mi Band Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun handlePairing(intent: Intent?) {
        actionConnect(intent)

        val d = miBand.pair().subscribe(
            { Timber.d("Pairing successful") },
            { throwable -> Timber.e(throwable, "Pairing failed") })
        disposables.add(d)

        startService()
    }

    private fun handleBatteryUpdate() = disposables.add(
        miBand.batteryInfo
            .subscribe({ batteryInfo ->
                setMiBandBatteryInfo(this, batteryInfo)
                Timber.d(batteryInfo.toString())
            }, { throwable -> Timber.e(throwable, "getBatteryInfo fail") }
            )
    )


    private fun handleAlert() {
        GlobalScope.launch(Dispatchers.IO) {
            actionStartVibration()
            actionStopVibration()
        }

        Intent(this, LockingService::class.java).also {
            it.action = LockingServiceActions.START.name
        }

        stopService()
    }

    //Communication action handlers

    private fun actionConnect(intent: Intent?) {
        val device = intent!!.getParcelableExtra<BluetoothDevice>("device")

        val d = miBand.connect(device!!)
            .subscribe({ result ->
                Timber.d("Connect onNext: $result")
                if (!result) {
                    actionConnect(intent)
                }
            }, { throwable ->
                throwable.printStackTrace()
                Timber.e(throwable)
            })
        disposables.add(d)
    }

    private fun actionEnableRealtimeStepsNotify() =
        disposables.add(miBand.enableRealtimeStepsNotify().subscribe())

    private fun actionDisableRealtimeStepsNotify() =
        disposables.add(miBand.disableRealtimeStepsNotify().subscribe())

    private fun actionEnableSensorDataNotify() =
        disposables.add(miBand.enableSensorDataNotify().subscribe())

    private fun actionDisableSensorDataNotify() =
        disposables.add(miBand.disableSensorDataNotify().subscribe())


    private fun actionSetHeartRateNotifyListener() =
        miBand.setHeartRateScanListener(object : HeartRateNotifyListener {
            override fun onNotify(heartRate: Int) {
                Timber.d("heart rate: $heartRate")

                GlobalScope.launch(Dispatchers.IO) {
                    heartRateRepository.insertHeartRateSample(
                        HeartRate(
                            Calendar.getInstance(),
                            heartRate
                        )
                    )
                }
            }
        })


    private fun actionSetRealtimeNotifyListener() =
        miBand.setRealtimeStepsNotifyListener(object : RealtimeStepsNotifyListener {
            override fun onNotify(steps: Int) {
                Timber.d("RealtimeStepsNotifyListener:$steps")

                GlobalScope.launch(Dispatchers.IO) {
                    stepRepository.insertBandStepSample(BandStep(Calendar.getInstance(), steps))
                }
            }
        })

    private fun actionSetSensorDataNotifyListener() = miBand.setSensorDataNotifyListener { data ->
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0

        val index = data[i++].toInt() and 0xFF or (data[i++].toInt() and 0xFF shl 8)
        val d1 = data[i++].toInt() and 0xFF or (data[i++].toInt() and 0xFF shl 8)
        val d2 = data[i++].toInt() and 0xFF or (data[i++].toInt() and 0xFF shl 8)
        val d3 = data[i++].toInt() and 0xFF or (data[i++].toInt() and 0xFF shl 8)

        Timber.d("$index , $d1 , $d2 , $d3")

        GlobalScope.launch(Dispatchers.IO) {
            sensorDataRepository.insertSensorDataSample(
                SensorData(
                    Calendar.getInstance(),
                    d1,
                    d2,
                    d3
                )
            )
        }
    }


    private fun actionStartHeartRateScan() =
        disposables.add(miBand.startHeartRateScan().subscribe())

    //used for alerting user of starting lockdown in events with discrepancies between steps or no hr
    private fun actionStartVibration() = disposables.add(
        miBand.startVibration(VibrationMode.VIBRATION_WITHOUT_LED)
            .subscribe { Timber.d("Vibration started") }
    )


    private fun actionStopVibration() {
        val d = miBand.stopVibration()
            .subscribe { Timber.d("Vibration stopped") }
        disposables.add(d)
    }

    //Step counter sensor callbacks

    override fun onSensorChanged(event: SensorEvent?) {
        TODO("Not yet implemented")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        TODO("Not yet implemented")
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "MI BAND SERVICE CHANNEL"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            "Mi Band Service notifications channel",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "Mi Band Service channel"
            it.enableLights(true)
            it.lightColor = Color.WHITE
            it.enableVibration(true)
            it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("Monitoring your device")
            .setContentText("Tap to open app")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.outline_track_changes_black_18dp)
            .setTicker(";)")
            .build()
    }

}
