package com.polidea.rxandroidble.internal.connection

import static rx.Observable.from
import static rx.Observable.just

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.polidea.rxandroidble.NotificationSetupMode
import com.polidea.rxandroidble.exceptions.BleCannotSetCharacteristicNotificationException
import com.polidea.rxandroidble.exceptions.BleConflictingNotificationAlreadySetException
import com.polidea.rxandroidble.internal.util.CharacteristicChangedEvent
import org.robolectric.annotation.Config
import org.robospock.RoboSpecification
import rx.Completable
import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import spock.lang.Unroll

@Config(manifest = Config.NONE)
class NotificationAndIndicationManagerTest extends RoboSpecification {

    public static final CHARACTERISTIC_UUID = UUID.fromString("f301f518-5414-471c-8a7b-2ef6d1b7373d")
    public static final CHARACTERISTIC_INSTANCE_ID = 1
    public static final OTHER_UUID = UUID.fromString("ab906173-5daa-4d6b-8604-c2be69122d57")
    public static final OTHER_INSTANCE_ID = 2
    public static final byte[] EMPTY_DATA = [] as byte[]
    public static final byte[] NOT_EMPTY_DATA = [1, 2, 3] as byte[]
    public static final byte[] OTHER_DATA = [2, 2, 3] as byte[]
    public static final byte[] ENABLE_NOTIFICATION_VALUE = [1] as byte[]
    public static final byte[] ENABLE_INDICATION_VALUE = [2] as byte[]
    public static final byte[] DISABLE_NOTIFICATION_VALUE = [3] as byte[]
    public static final boolean[] ACK_VALUES = [true, false]
    public static final NotificationSetupMode[] ALL_MODES = NotificationSetupMode.values()
    public static final NotificationSetupMode[] NON_COMPAT_MODES = [NotificationSetupMode.DEFAULT, NotificationSetupMode.QUICK_SETUP]

    def bluetoothGattMock = Mock(BluetoothGatt)
    def rxBleGattCallbackMock = Mock(RxBleGattCallback)
    def descriptorWriterMock = Mock(DescriptorWriter)
    def disconnectedErrorBehaviourSubject = BehaviorSubject.create()

    NotificationAndIndicationManager objectUnderTest
    def testSubscriber = new TestSubscriber()

    def setup() {
        rxBleGattCallbackMock.observeDisconnect() >> disconnectedErrorBehaviourSubject
        objectUnderTest = new NotificationAndIndicationManager(
                ENABLE_NOTIFICATION_VALUE,
                ENABLE_INDICATION_VALUE,
                DISABLE_NOTIFICATION_VALUE,
                bluetoothGattMock,
                rxBleGattCallbackMock,
                descriptorWriterMock)
    }

    @Unroll
    def "should emit BleCannotSetCharacteristicNotificationException with CANNOT_FIND_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR reason if CLIENT_CONFIGURATION_DESCRIPTION wasn't found when in DEFAULT mode"() {

        given:
        descriptorWriterMock.writeDescriptor(_, _) >> just(new byte[0])
        bluetoothGattMock.setCharacteristicNotification(_, _) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.empty()
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        characteristic.getDescriptor(_) >> null

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.DEFAULT, ack).subscribe(testSubscriber)

        then:
        testSubscriber.assertError {
            BleCannotSetCharacteristicNotificationException e -> e.getReason() == BleCannotSetCharacteristicNotificationException.CANNOT_FIND_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR
        }

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should setup notification even if CLIENT_CONFIGURATION_DESCRIPTION wasn't found when in COMPAT mode"() {

        given:
        descriptorWriterMock.writeDescriptor(_, _) >> just(new byte[0])
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.empty()
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        characteristic.getDescriptor(_) >> null

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.COMPAT, ack).subscribe(testSubscriber)

        then:
        1 * bluetoothGattMock.setCharacteristicNotification(_, _) >> true
        testSubscriber.assertValueCount(1)

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should emit Observable<byte[]> before DescriptorWriter.writeDescriptor() emits when in QUICK_SETUP mode"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        descriptorWriterMock.writeDescriptor(_, _) >> Observable.never()
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true

        when:
        def testSubscriber = objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).test()

        then:
        testSubscriber.assertValueCount(1)

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should emit BleCannotSetCharacteristicNotificationException with CANNOT_SET_LOCAL_NOTIFICATION reason if failed to set characteristic notification"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        descriptorWriterMock.writeDescriptor(_, _) >> Observable.empty()
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.empty()
        mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> false

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(testSubscriber)

        then:
        testSubscriber.assertError {
            BleCannotSetCharacteristicNotificationException e -> e.getReason() == BleCannotSetCharacteristicNotificationException.CANNOT_SET_LOCAL_NOTIFICATION
        }

        where:
        [ack, mode] << [ACK_VALUES, ALL_MODES].combinations()
    }

    @Unroll
    def "should emit BleCannotSetCharacteristicNotificationException with CANNOT_WRITE_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR reason and a cause if failed to write successfully CCC Descriptor when in DEFAULT mode"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.empty()
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        def testExceptionCause = new RuntimeException()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> Observable.error(testExceptionCause)

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.DEFAULT, ack).subscribe(testSubscriber)

        then:
        testSubscriber.assertError {
            BleCannotSetCharacteristicNotificationException e ->
                e.getReason() == BleCannotSetCharacteristicNotificationException.CANNOT_WRITE_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR &&
                        e.getCause() == testExceptionCause
        }

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should emit BleCannotSetCharacteristicNotificationException with CANNOT_WRITE_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR reason and a cause if failed to write successfully CCC Descriptor (from the emitted Observable<byte>) when in QUICK_SETUP mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<byte[]> descriptorWriteResult = PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResult
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)
        notificationObservable.subscribe()
        def testExceptionCause = new RuntimeException("test")

        when:
        descriptorWriteResult.onError(testExceptionCause)

        then:
        testSubscriber.assertError {
            Throwable e ->
                e instanceof BleCannotSetCharacteristicNotificationException &&
                        e.getReason() == BleCannotSetCharacteristicNotificationException.CANNOT_WRITE_CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR &&
                        e.getCause() == testExceptionCause
        }

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should complete the emitted io.reactivex.Observable<byte> when an error happens while writing CCC in QUICK_SETUP mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<byte[]> descriptorWriteResult = PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResult
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)
        def childTestSubscriber = new TestSubscriber<>()
        notificationObservable.subscribe(childTestSubscriber)

        when:
        descriptorWriteResult.onError(new RuntimeException("test"))

        then:
        childTestSubscriber.assertCompleted()

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should subscribe to DescriptorWriter.writeDescriptor() only after subscription to the emitted io.reactivex.Observable<byte[]> is made in QUICK_SETUP mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<byte[]> descriptorWriteResult = PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResult
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)
        def childTestSubscriber = new TestSubscriber<>()

        expect:
        !descriptorWriteResult.hasObservers()

        when:
        notificationObservable.subscribe(childTestSubscriber)

        then:
        descriptorWriteResult.hasObservers()

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should not subscribe to DescriptorWriter.writeDescriptor() after subscription to the parent io.reactivex.Observable<Observable<byte[]>> was unsubscribed in QUICK_SETUP mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<byte[]> descriptorWriteResult = PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResult.ignoreElements()
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)
        testSubscriber.unsubscribe()

        when:
        notificationObservable.subscribe()

        then:
        !descriptorWriteResult.hasObservers()

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should not subscribe to DescriptorWriter.writeDescriptor() twice in QUICK_SETUP mode when more than one subscription is made to the child io.reactivex.Observable<byte[]> ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<byte[]> descriptorWriteResult = PublishSubject.create()
        Observable<byte[]> descriptorWriteResultObservable = descriptorWriteResult.publish().autoConnect(2).ignoreElements()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResultObservable
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)

        when:
        notificationObservable.subscribe()
        notificationObservable.subscribe()

        then:
        !descriptorWriteResult.hasObservers()

        where:
        ack << ACK_VALUES
    }

    @Unroll
    def "should not subscribe again to DescriptorWriter.writeDescriptor() if first subscription finished with '#result' in QUICK_SETUP mode ack:#ack"() {
        given:
        def completableForResultGetter = { if (it == "complete") Completable.complete() else Completable.error(new RuntimeException("Test")) }
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        PublishSubject<Completable> descriptorWriteResult = PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> descriptorWriteResult.take(1).flatMapCompletable({ it })
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, NotificationSetupMode.QUICK_SETUP, ack).subscribe(testSubscriber)
        def notificationObservable = testSubscriber.onNextEvents.get(0)
        def subscription = notificationObservable.subscribe()
        descriptorWriteResult.onNext(completableForResultGetter(result))
        subscription.unsubscribe()

        when:
        notificationObservable.subscribe()

        then:
        !descriptorWriteResult.hasObservers()

        where:
        [ack, result] << [ACK_VALUES, ["complete", "error"]].combinations()
    }

    @Unroll
    def "should proxy RxBleGattCallback.observeDisconnect() if happened before .subscribe()"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        def testException = new RuntimeException("test")
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        disconnectedErrorBehaviourSubject.onError(testException)

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(testSubscriber)

        then:
        testSubscriber.assertError(testException)

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should proxy RxBleGattCallback.observeDisconnect() if happened after Observable<byte[]> emission"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        def testException = new RuntimeException("test")
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(testSubscriber)

        when:
        disconnectedErrorBehaviourSubject.onError(testException)

        then:
        testSubscriber.assertValueCount(1)
        testSubscriber.assertError(testException)

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should write proper value to CCC Descriptor when in non COMPAT mode mode:#mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(testSubscriber)

        then:
        1 * descriptorWriterMock.writeDescriptor(descriptor, enableValueForAck(ack)) >> Observable.empty()

        where:
        [mode, ack] << [NON_COMPAT_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should notify about value change and stay subscribed"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> from(changeNotificationsAndExpectedValues.collect {
            new CharacteristicChangedEvent(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID, it)
        })

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).flatMap({ it }).subscribe(testSubscriber)

        then:
        testSubscriber.assertValues(changeNotificationsAndExpectedValues)
        testSubscriber.assertNotCompleted()

        where:
        [changeNotificationsAndExpectedValues, mode, ack] << [
                [[NOT_EMPTY_DATA], [NOT_EMPTY_DATA, OTHER_DATA]],
                ALL_MODES,
                ACK_VALUES
        ].combinations()
    }

    @Unroll
    def "should not notify about value change if UUID and / or instanceId is not matching"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        bluetoothGattMock.getOnCharacteristicChanged() >> just(otherCharacteristicNotificationId)

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).flatMap({ it }).subscribe(testSubscriber)

        then:
        testSubscriber.assertNoValues()
        testSubscriber.assertNotCompleted()

        where:
        [mode, ack, otherCharacteristicNotificationId] << [
                ALL_MODES,
                ACK_VALUES,
                [
                        new CharacteristicChangedEvent(CHARACTERISTIC_UUID, OTHER_INSTANCE_ID, NOT_EMPTY_DATA),
                        new CharacteristicChangedEvent(OTHER_UUID, CHARACTERISTIC_INSTANCE_ID, NOT_EMPTY_DATA),
                        new CharacteristicChangedEvent(OTHER_UUID, OTHER_INSTANCE_ID, NOT_EMPTY_DATA)
                ]
        ].combinations()
    }

    @Unroll
    def "should not setup another notification if one was already done on the same characteristic"() {
        given:
        def secondSubscriber = new TestSubscriber()
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> just(new byte[0])

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(testSubscriber)
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(secondSubscriber)

        then:
        1 * bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true

        and:
        testSubscriber.assertValueCount(1)
        testSubscriber.assertNoErrors()
        secondSubscriber.assertValueCount(1)
        secondSubscriber.assertNoErrors()

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should not setup another notification if one was already done on the same characteristic even if not subscribed yet"() {
        given:
        def secondSubscriber = new TestSubscriber()
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> PublishSubject.create()
        descriptorWriterMock.writeDescriptor(descriptor, _) >> just(new byte[0])
        def firstObservable = objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack)
        def secondObservable = objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack)

        when:
        firstObservable.subscribe(testSubscriber)
        secondObservable.subscribe(secondSubscriber)

        then:
        1 * bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true

        and:
        testSubscriber.assertValueCount(1)
        testSubscriber.assertNoErrors()
        secondSubscriber.assertValueCount(1)
        secondSubscriber.assertNoErrors()

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should notify both subscribers about value change"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        def characteristicChangeSubject = PublishSubject.create()
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> characteristicChangeSubject
        def secondSubscriber = new TestSubscriber()
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).flatMap({ it }).subscribe(testSubscriber)
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).flatMap({ it }).subscribe(secondSubscriber)

        when:
        characteristicChangeSubject.onNext(new CharacteristicChangedEvent(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID, NOT_EMPTY_DATA))

        then:
        testSubscriber.assertValue(NOT_EMPTY_DATA)
        secondSubscriber.assertValue(NOT_EMPTY_DATA)

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should unregister notifications after all observers are unsubscribed mode:#mode ack:#ack"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        1 * bluetoothGattMock.setCharacteristicNotification(characteristic, true) >> true
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> PublishSubject.create()
        def secondSubscriber = new TestSubscriber()

        when:
        def firstSubscription = objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe()
        def secondSubscription = objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack).subscribe(secondSubscriber)

        then:
        writerCalls * descriptorWriterMock.writeDescriptor(descriptor, { it == enableValueForAck(ack) }) >> just(new byte[0])

        when:
        firstSubscription.unsubscribe()

        then:
        0 * bluetoothGattMock.setCharacteristicNotification(characteristic, false) >> true
        0 * descriptorWriterMock.writeDescriptor(descriptor, _) >> just(new byte[0])

        when:
        secondSubscription.unsubscribe()

        then:
        1 * bluetoothGattMock.setCharacteristicNotification(characteristic, false) >> true
        writerCalls * descriptorWriterMock.writeDescriptor(descriptor, { it == DISABLE_NOTIFICATION_VALUE }) >> just(new byte[0])

        where:
        mode                              | ack   | writerCalls
        NotificationSetupMode.DEFAULT     | true  | 1
        NotificationSetupMode.DEFAULT     | false | 1
        NotificationSetupMode.COMPAT      | true  | 0
        NotificationSetupMode.COMPAT      | false | 0
        NotificationSetupMode.QUICK_SETUP | true  | 1
        NotificationSetupMode.QUICK_SETUP | false | 1
    }

    @Unroll
    def "should emit BleCharacteristicNotificationOfOtherTypeAlreadySetException if notification is set up after indication on the same characteristic"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> PublishSubject.create()
        def secondSubscriber = new TestSubscriber()
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode0, acks[0]).subscribe(testSubscriber)

        when:
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode1, acks[1]).subscribe(secondSubscriber)

        then:
        testSubscriber.assertNoErrors()
        secondSubscriber.assertError(BleConflictingNotificationAlreadySetException)

        where:
        [mode0, mode1, acks] << [
                ALL_MODES,
                ALL_MODES,
                [[true, false], [false, true]]
        ].combinations()
    }

    @Unroll
    def "should complete the emitted Observable<byte> when unsubscribed"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.never()
        def emittedObservableSubscriber = new TestSubscriber()
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack)
                .doOnNext { it.subscribe(emittedObservableSubscriber) }
                .subscribe(testSubscriber)

        when:
        testSubscriber.unsubscribe()

        then:
        emittedObservableSubscriber.assertCompleted()

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    @Unroll
    def "should proxy the error emitted by RxBleGattCallback.getOnCharacteristicChanged() to emitted Observable<byte>"() {
        given:
        def characteristic = shouldSetupCharacteristicNotificationCorrectly(CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID)
        def testException = new RuntimeException("test")
        rxBleGattCallbackMock.getOnCharacteristicChanged() >> Observable.error(testException)
        objectUnderTest.setupServerInitiatedCharacteristicRead(characteristic, mode, ack)
                .doOnNext { it.subscribe(testSubscriber) }
                .subscribe(new TestSubscriber<Observable<byte[]>>())

        when:
        disconnectedErrorBehaviourSubject.onError(testException)

        then:
        testSubscriber.assertError(testException)

        where:
        [mode, ack] << [ALL_MODES, ACK_VALUES].combinations()
    }

    def mockCharacteristicWithValue(Map characteristicData) {
        def characteristic = Mock BluetoothGattCharacteristic
        characteristic.getValue() >> characteristicData['value']
        characteristic.getUuid() >> characteristicData['uuid']
        characteristic.getInstanceId() >> characteristicData['instanceId']
        characteristic
    }

    def mockDescriptorAndAttachToCharacteristic(BluetoothGattCharacteristic characteristic) {
        def descriptor = Spy(BluetoothGattDescriptor, constructorArgs: [NotificationAndIndicationManager.CLIENT_CHARACTERISTIC_CONFIG_UUID, 0])
        descriptor.getCharacteristic() >> characteristic
        characteristic.getDescriptor(NotificationAndIndicationManager.CLIENT_CHARACTERISTIC_CONFIG_UUID) >> descriptor
        descriptor
    }

    def shouldSetupCharacteristicNotificationCorrectly(UUID characteristicUUID, int instanceId) {
        def characteristic = mockCharacteristicWithValue(uuid: characteristicUUID, instanceId: instanceId, value: EMPTY_DATA)
        def descriptor = mockDescriptorAndAttachToCharacteristic(characteristic)
        descriptorWriterMock.writeDescriptor(descriptor, _) >> just(new byte[0])
        bluetoothGattMock.setCharacteristicNotification(characteristic, _) >> true
        characteristic
    }

    def enableValueForAck(boolean ack) {
        return ack ? ENABLE_INDICATION_VALUE : ENABLE_NOTIFICATION_VALUE
    }
}
