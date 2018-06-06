package io.seraphim.headsandhands.example

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.support.annotation.IdRes
import android.support.annotation.IntDef
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.bluelinelabs.conductor.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

object Arch {
    @JvmField val mainThreadId = Looper.getMainLooper().thread.id
    @JvmField val mainHandler = Handler(Looper.getMainLooper())
    @JvmField val messageId = AtomicInteger(1)

    inline fun onMainThread(crossinline action: () -> Unit) {
        if (Thread.currentThread().id == mainThreadId) action()
        else mainHandler.post { action() }
    }

    fun generateMessageId(): Int = messageId.incrementAndGet()
}

abstract class ArchActivity : AppCompatActivity() {
    protected lateinit var router: Router

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = ChangeHandlerFrameLayout(this)
        container.id = R.id.arch_controller_container
        container.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContentView(container)

        onPreAttachRouter()

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            val rootController = provideRootController()
            if (rootController != null) {
                router.setRoot(RouterTransaction.with(rootController))
            }
        }
    }

    protected open fun onPreAttachRouter() {}

    override fun onBackPressed() {
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }

    protected abstract fun provideRootController(): Controller?
}

abstract class ArchController<P : ArchPresenter> : Controller {
    protected val handler = Handler(Looper.getMainLooper())
    protected val presenter = providePresenter()

    constructor() : super()
    constructor(args: Bundle?) : super(args)

    init {
        presenter.create()
        addLifecycleListener(object : Controller.LifecycleListener() {
            override fun postAttach(controller: Controller, view: View) {
                presenter.attach()
            }

            override fun postDetach(controller: Controller, view: View) {
                presenter.detach()
            }

            override fun postDestroy(controller: Controller) {
                presenter.destroy()
            }

            override fun postContextAvailable(controller: Controller, context: Context) {
                properties().forEach { it.init(controller as ArchController<*>) }
            }
        })
    }

    protected open fun properties(): List<ArchProperty<*>> = emptyList()

    protected abstract fun providePresenter(): P

    protected abstract fun provideLayoutRes(): Int

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(provideLayoutRes(), container, false)
    }

    protected fun push(
            controller: Controller,
            pushChangeHandler: ControllerChangeHandler? = null,
            popChangeHandler: ControllerChangeHandler? = null
    ) {
        router.pushController(RouterTransaction.with(controller)
                .pushChangeHandler(pushChangeHandler)
                .popChangeHandler(popChangeHandler))
    }
}

abstract class ArchPresenter {
    companion object {
        const val INITIALIZED = 0
        const val CREATED = 1
        const val DETACHED = 2
        const val ATTACHED = 3
        const val DESTROYED = 4
    }

    @JvmField val uuid = UUID.randomUUID()
    @JvmField protected val disposables = CompositeDisposable()
    private var state = INITIALIZED

    val isCreated: Boolean get() = state == DETACHED
    val isAttached: Boolean get() = state == ATTACHED
    val isDetached: Boolean get() = state == DETACHED
    val isDestroyed: Boolean get() = state == DESTROYED

    @IntDef(INITIALIZED, CREATED, DETACHED, ATTACHED, DESTROYED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State

    fun create() {
        if (moveToState(CREATED)) onCreate()
    }

    fun attach() {
        if (isMoveAllowed(state, ATTACHED)) {
            if (moveToState(ATTACHED)) onAttach()
        }
    }

    fun detach() {
        if (moveToState(DETACHED)) onDetach()
    }

    fun destroy() {
        if (moveToState(DESTROYED)) onDestroy()
        disposables.dispose()
    }

    override fun toString() = javaClass.simpleName + " [ " + uuid.toString() + " ]"

    protected fun Disposable.manage(): Disposable {
        disposables.add(this)
        return this
    }

    protected fun onCreate() {}

    protected fun onAttach() {}

    protected fun onDetach() {}

    protected fun onDestroy() {}

    private fun moveToState(@State newState: Int): Boolean {
        @State val oldState = state

        if (isMoveAllowed(oldState, newState)) {
            state = newState
            return true
        }

        return false
    }

    private fun isMoveAllowed(@State oldState: Int, @State newState: Int): Boolean {
        if (newState != oldState) {
            if (oldState == INITIALIZED && newState == CREATED)
                return true
            else if (oldState == CREATED && newState == ATTACHED)
                return true
            else if (oldState == DETACHED && (newState == ATTACHED || newState == DESTROYED))
                return true
            else if (oldState == ATTACHED && newState == DETACHED)
                return true
            else if (oldState == DESTROYED) return false
        }

        return false
    }

    private fun nameOfState(state: Int) = when (state) {
        0 -> "INITIALIZED"
        1 -> "CREATED"
        2 -> "DETACHED"
        3 -> "ATTACHED"
        4 -> "DESTROYED"
        else -> "UNKNOWN"
    }
}

abstract class ArchProperty<T : Any> : Handler.Callback {
    protected var reference: WeakReference<T?>? = null
    protected val handler by lazy { Handler(Looper.getMainLooper(), this) }
    protected var target: T?
        get() = reference?.get()
        private set(value) {
            reference = if (value == null) null else WeakReference(value)
        }

    open fun init(controller: ArchController<*>) {}

    protected inline fun onMainThread(crossinline action: () -> Unit) = Arch.onMainThread(action)

    open fun bind(obj: T): T {
        target = obj
        onBind(obj)
        return obj
    }

    protected open fun onBind(target: T) {}

    protected inline fun applyOnTarget(action: T.() -> Unit) {
        action(target ?: return)
    }

    override fun handleMessage(message: Message): Boolean = true
}

abstract class ArchViewProperty<V : View> : ArchProperty<V>() {
    open fun bind(container: View, @IdRes res: Int): V {
        return bind(container.findViewById(res))
    }

    open fun createAndBind(context: Context): V {
        val view = createView(context)
        bind(view)
        return view
    }

    protected open fun createView(context: Context): V = throw NotImplementedError("${this::class.java.simpleName}.createView is not implemented")

    protected inline fun postOnView(crossinline action: V.() -> Unit) {
        val view = target ?: return
        view.post { action(view) }
    }
}

object EmptyPresenter : ArchPresenter()