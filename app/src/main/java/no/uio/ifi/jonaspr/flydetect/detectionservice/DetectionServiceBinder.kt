package no.uio.ifi.jonaspr.flydetect.detectionservice

import android.os.Binder
import java.lang.ref.WeakReference

class DetectionServiceBinder(service: DetectionService): Binder() {
    private val service = WeakReference(service)
    fun stop() = service.get()!!.stop()
    fun latestAccSample() = service.get()!!.accListener.latest
    fun latestBarSample() = service.get()!!.barListener.latest
    fun replayProgress() = service.get()!!.sensorManager?.getReplayProgress() ?: 0
    fun flying() = service.get()!!.decisionComponent.currentlyFlying()
    fun flyingLiveData() = service.get()!!.decisionComponent.flyingLiveData()
    fun forceFlight(x: Boolean) = service.get()!!.decisionComponent.setFlyingStatus(x)
    fun flightStats() = service.get()!!.getStats()
    fun isUsingSensorInjection() = service.get()!!.isUsingSensorInjection
    fun secondsUntilNextAnalysis() = service.get()!!.decisionComponent.secondsUntilNextAnalysis()
}