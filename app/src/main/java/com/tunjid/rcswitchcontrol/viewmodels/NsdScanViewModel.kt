/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid.rcswitchcontrol.viewmodels

import android.app.Application
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.recyclerview.widget.DiffUtil
import com.tunjid.androidbootstrap.communications.nsd.NsdHelper
import com.tunjid.androidbootstrap.functions.collections.Lists
import com.tunjid.androidbootstrap.recyclerview.diff.Diff
import com.tunjid.androidbootstrap.recyclerview.diff.Differentiable
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers.mainThread
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import java.util.*
import java.util.concurrent.TimeUnit

class NsdScanViewModel(application: Application) : AndroidViewModel(application) {

    val services: MutableList<NsdServiceInfo>

    private val nsdHelper: NsdHelper
    private var processor: PublishProcessor<Diff<NsdServiceInfo>>? = null

    init {

        nsdHelper = NsdHelper.getBuilder(getApplication())
                .setServiceFoundConsumer(this::onServiceFound)
                .setResolveSuccessConsumer(this::onServiceResolved)
                .setResolveErrorConsumer(this::onServiceResolutionFailed)
                .build()

        services = ArrayList()
        reset()
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stopServiceDiscovery()
        nsdHelper.tearDown()
    }

    fun findDevices(): Flowable<DiffUtil.DiffResult> {
        reset()
        nsdHelper.discoverServices()

        // Clear list first, then start scanning.
        return Flowable.fromCallable {
            Diff.calculate(services,
                    emptyList(),
                    { _, _ -> emptyList() },
                    { info -> Differentiable.fromCharSequence { info.serviceName } })
        }
                .concatWith(processor!!.take(SCAN_PERIOD, TimeUnit.SECONDS, Schedulers.io()))
                .subscribeOn(Schedulers.io())
                .observeOn(mainThread()).map { diff ->
                    Lists.replace(services, diff.items)
                    diff.result
                }
    }

    fun stopScanning() {
        if (processor == null)
            processor = PublishProcessor.create()
        else if (!processor!!.hasComplete()) processor!!.onComplete()

        nsdHelper.stopServiceDiscovery()
    }

    private fun reset() {
        stopScanning()
        processor = PublishProcessor.create()
    }

    private fun onServiceFound(service: NsdServiceInfo) {
        nsdHelper.resolveService(service)
    }

    private fun onServiceResolutionFailed(service: NsdServiceInfo, errorCode: Int) {
        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) nsdHelper.resolveService(service)
    }

    private fun onServiceResolved(service: NsdServiceInfo) {
        if (!processor!!.hasComplete())
            processor!!.onNext(Diff.calculate(
                    services,
                    listOf(service),
                    { currentServices, foundServices -> this.addServices(currentServices, foundServices) },
                    { info -> Differentiable.fromCharSequence { info.serviceName } }))
    }

    private fun addServices(currentServices: MutableList<NsdServiceInfo>, foundServices: List<NsdServiceInfo>): List<NsdServiceInfo> {
        val union = Lists.union<NsdServiceInfo, String>(currentServices, foundServices) { it.serviceName }
        Lists.replace(currentServices, union)
        currentServices.sortWith(Comparator { a, b -> a.serviceName.compareTo(b.serviceName) })
        return currentServices
    }

    companion object {

        private const val SCAN_PERIOD: Long = 10
    }
}
