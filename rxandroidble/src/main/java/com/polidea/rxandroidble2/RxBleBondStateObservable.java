package com.polidea.rxandroidble2;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.NonNull;

import bleshadow.javax.inject.Inject;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.functions.Cancellable;
import io.reactivex.schedulers.Schedulers;

/**
 * Observes Bluetooth device bond state.
 * <p>
 * NOTE: Make sure that this Observable is unsubscribed according to the Activity lifecycle. It internally uses BroadcastReceiver, so
 * it is required that it us unregistered before onDestroy.
 */
public class RxBleBondStateObservable extends Observable<RxBleBondStateObservable.BleBondState> {

    public static class BleBondStateChange {
        private final int oldState;
        private final int newState;

        private BleBondStateChange(int oldState, int newState) {
            this.oldState = oldState;
            this.newState = newState;
        }

        private String getStateName(int state) {
            if (state == BluetoothDevice.BOND_BONDING) {
                return "BOND_BONDING";
            } else if (state == BluetoothDevice.BOND_BONDED) {
                return "BOND_BONDED";
            } else {
                return "BOND_NONE";
            }
        }

        @Override
        @NonNull
        public String toString() {
            return "BleBondStateChange: " + getStateName(oldState) + " to " + getStateName(newState);
        }
    }

    @NonNull
    private final Observable<BleBondState> bleBondStateObservable;

    @Inject
    public RxBleBondStateObservable(@NonNull final Context context) {
        bleBondStateObservable = Observable.create(new ObservableOnSubscribe<BleBondState>() {
            @Override
            public void subscribe(final ObservableEmitter<BleBondState> emitter) {
                final BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                        int previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                        if (previousState != BluetoothDevice.BOND_BONDING) {
                            return;
                        }
                        if (state == BluetoothDevice.BOND_BONDING) {
                            return;
                        }
                        emitter.onNext(BleBondStateChange(previousState, state));
                    }
                };
                context.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
                emitter.setCancellable(new Cancellable() {
                    @Override
                    public void cancel() {
                        context.unregisterReceiver(receiver);
                    }
                });
            }
        })
                .subscribeOn(Schedulers.trampoline())
                .unsubscribeOn(Schedulers.trampoline())
                .share();
    }

    @Override
    protected void subscribeActual(final Observer<? super BleBondState> observer) {
        bleBondStateObservable.subscribe(observer);
    }
}
