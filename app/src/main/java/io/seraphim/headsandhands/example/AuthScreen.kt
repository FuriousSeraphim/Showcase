package io.seraphim.headsandhands.example

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.design.widget.CheckableImageButton
import android.support.design.widget.Snackbar
import android.support.design.widget.TextInputLayout
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v4.util.PatternsCompat
import android.support.v7.widget.Toolbar
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.bluelinelabs.conductor.Controller
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AuthScreen : ArchController<AuthPresenter> {
    constructor() : super()
    constructor(args: Bundle?) : super(args)

    private val toolbar = ToolbarProperty()
    private val email = EmailInputProperty()
    private val password = PasswordInputProperty()
    private val weatherSnackbar = WeatherSnackbarProperty()

    override fun providePresenter() = AuthPresenter()
    override fun provideLayoutRes() = R.layout.screen_auth
    override fun properties() = listOf<ArchProperty<*>>(toolbar, email, password, weatherSnackbar)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        val view = super.onCreateView(inflater, container)

        toolbar.bind(view, R.id.auth_toolbar)
        email.bind(view, R.id.auth_email_edit_text)
        password.bind(view, R.id.auth_password_edit_text)
        weatherSnackbar.bind(view, R.id.auth_root)

        view.findViewById<CheckableImageButton>(R.id.auth_password_help).apply {
            setImageResource(R.drawable.help_circle_outline)
            setOnClickListener(object : View.OnClickListener {
                private var snackbar: Snackbar? = null

                override fun onClick(view: View) {
                    if (snackbar == null) snackbar = Snackbar.make(
                            view,
                            R.string.password_help_hint,
                            TimeUnit.SECONDS.toMillis(3).toInt()
                    ).setAction("OK") { snackbar?.dismiss() }
                    snackbar?.show()
                }
            })
        }

        view.findViewById<Button>(R.id.auth_sign_in).setOnClickListener {
            //            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            if (email.validateOrShowError() && password.validateOrShowError()) {
                presenter.loadWeather(weatherSnackbar)
            }
        }

        return view
    }

    private inner class ToolbarProperty : ArchViewProperty<Toolbar>(), View.OnClickListener, MenuItem.OnMenuItemClickListener {
        private val menuItemText = SpannableStringBuilder().append("Создать", ForegroundColorSpan(Color.parseColor("#4E92DF")), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        private var icon: Drawable? = null

        override fun onBind(target: Toolbar) {
            if (icon == null) icon = ContextCompat.getDrawable(target.context, R.drawable.arrow_left)
                    ?.let { DrawableCompat.wrap(it) }
                    ?.apply { DrawableCompat.setTint(this, Color.parseColor("#4E92DF")) }
            target.navigationIcon = icon
            target.setNavigationOnClickListener(this)
            target.menu.add(menuItemText)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    .setOnMenuItemClickListener(this)
        }

        override fun onClick(v: View?) {
            router.popCurrentController()
        }

        override fun onMenuItemClick(item: MenuItem?): Boolean {
            return true
        }
    }

    private inner class EmailInputProperty : TextInput() {
        override fun pattern() = PatternsCompat.EMAIL_ADDRESS
        override fun inputViewId() = R.id.auth_email_input_layout
        override fun errorTextRes() = R.string.email_validation_error
    }

    private inner class PasswordInputProperty : TextInput() {
        private val pattern = Pattern.compile("^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[a-zA-Z]).{6,}\$")
        override fun pattern() = pattern
        override fun inputViewId() = R.id.auth_password_input_layout
        override fun errorTextRes() = R.string.password_validation_error
    }

    private inner class WeatherSnackbarProperty : ArchViewProperty<View>(), WeatherDisplayer {
        private var snackbar: Snackbar? = null
        @JvmField val glide = Glide.with(App.context).applyDefaultRequestOptions(RequestOptions
                .diskCacheStrategyOf(DiskCacheStrategy.DATA)
                .centerCrop())

        init {
            addLifecycleListener(object : LifecycleListener() {
                override fun preDestroyView(controller: Controller, view: View) {
                    snackbar = null
                }

                override fun postDestroy(controller: Controller) {
                    controller.removeLifecycleListener(this)
                }
            })
        }

        override fun showWeather(city: City) = onMainThread {
            val snackbar = createOrGetSnackbar() ?: return@onMainThread
            val view = snackbar.view

            glide.load(city.weather.icon).into(view.findViewById(R.id.weather_icon))
            view.findViewById<TextView>(R.id.weather_city_name).text = city.name
            view.findViewById<TextView>(R.id.weather_temperature).text = "${city.temperature.value.toInt()}\u2103"
            view.findViewById<TextView>(R.id.weather_description).text = city.weather.description

            snackbar.show()
        }

        private fun createOrGetSnackbar(): Snackbar? {
            val view = target ?: return null
            val snackbar = Snackbar.make(view, "", TimeUnit.SECONDS.toMillis(5).toInt())
            val snackbarContent = snackbar.view as ViewGroup
            snackbarContent.findViewById<View>(android.support.design.R.id.snackbar_text).visibility = View.INVISIBLE
            snackbarContent.findViewById<View>(android.support.design.R.id.snackbar_action).visibility = View.INVISIBLE
            val customView = LayoutInflater.from(view.context).inflate(R.layout.weather_snackbar, snackbarContent, false)
            snackbarContent.addView(customView, 0)
            this.snackbar = snackbar
            return snackbar
        }
    }

    private abstract inner class TextInput : ArchViewProperty<EditText>(), TextWatcher {
        @JvmField protected var text = ""
        private var inputRef: WeakReference<TextInputLayout>? = null

        init {
            addLifecycleListener(object : LifecycleListener() {
                override fun preDestroyView(controller: Controller, view: View) {
                    inputRef = null
                }

                override fun postDestroy(controller: Controller) {
                    controller.removeLifecycleListener(this)
                }
            })
        }

        abstract fun pattern(): Pattern
        abstract fun inputViewId(): Int
        abstract fun errorTextRes(): Int

        override fun bind(container: View, res: Int): EditText {
            inputRef = WeakReference(container.findViewById(inputViewId()))
            return super.bind(container, res)
        }

        override fun onBind(target: EditText) {
            target.setText(text)
            target.addTextChangedListener(this)
        }

        fun validate() = pattern().matcher(text).matches()

        fun validateOrShowError(): Boolean {
            val result = validate()
            if (result.not()) target?.apply { Snackbar.make(this, errorTextRes(), Snackbar.LENGTH_SHORT).show() }
            return result
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            text = s?.toString() ?: ""
            if (text.isNotBlank() && validate().not()) inputRef?.get()?.apply {
                isErrorEnabled = true
                error = context.getString(errorTextRes())
            } else inputRef?.get()?.isErrorEnabled = false
        }
    }

    interface WeatherDisplayer {
        fun showWeather(city: City)
    }
}

class AuthPresenter : ArchPresenter() {
    fun loadWeather(weatherDisplayer: AuthScreen.WeatherDisplayer) {
        Requests.get()
                .url("http://api.openweathermap.org/data/2.5/weather") {
                    addParam("q", "London")
                    addParam("units", "metric")
                    addParam("appid", "2febee4bcd0527c30956406331783835")
                }
                .prepareForCall(City::class)
                .asSingle()
                .subscribe({ weatherDisplayer.showWeather(it) }, {})
                .manage()
    }
}