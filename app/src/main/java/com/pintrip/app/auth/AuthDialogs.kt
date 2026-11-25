package com.pintrip.app.auth

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.pintrip.app.R

/**
 * 프로필 닉네임 입력 다이얼로그 (회전 시 입력값 보존 및 빈 값 입력 시 창 유지).
 */
class ProfileNicknameDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ProfileNicknameDialogFragment"
        private const val KEY_NICKNAME_INPUT = "key_nickname_input"

        fun newInstance(): ProfileNicknameDialogFragment {
            return ProfileNicknameDialogFragment()
        }
    }

    interface Listener {
        fun onSaveNickname(nickname: String)
        fun onSignOutFromNicknameDialog()
    }

    private var listener: Listener? = null
    private var inputEditText: EditText? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.nickname_hint)
            savedInstanceState?.getString(KEY_NICKNAME_INPUT)?.let { setText(it) }
        }
        inputEditText = input

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.login_dialog_profile_title))
            .setMessage(getString(R.string.login_dialog_profile_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.login_btn_save), null)
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                listener?.onSignOutFromNicknameDialog()
            }
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alertDialog = dialog as? AlertDialog ?: return
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val nickname = inputEditText?.text?.toString()?.trim().orEmpty()
            if (nickname.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.login_error_empty_nickname),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                listener?.onSaveNickname(nickname)
                dismiss()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        inputEditText?.text?.toString()?.let {
            outState.putString(KEY_NICKNAME_INPUT, it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        inputEditText = null
    }
}

/**
 * 프로필 조회/저장 오류 다이얼로그 (DialogFragment로 수명 관리).
 */
class ProfileErrorDialogFragment : DialogFragment() {

    companion object {
        const val TAG_LOAD_ERROR = "ProfileLoadErrorDialogFragment"
        const val TAG_SAVE_ERROR = "ProfileSaveErrorDialogFragment"

        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_MSG_RES = "arg_msg_res"
        private const val ARG_IS_SAVE_ERROR = "arg_is_save_error"

        fun newInstance(titleResId: Int, messageResId: Int, isSaveError: Boolean = false): ProfileErrorDialogFragment {
            return ProfileErrorDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, titleResId)
                    putInt(ARG_MSG_RES, messageResId)
                    putBoolean(ARG_IS_SAVE_ERROR, isSaveError)
                }
            }
        }
    }

    interface Listener {
        fun onRetryProfile(isSaveError: Boolean)
        fun onSignOutFromErrorDialog()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val titleRes = requireArguments().getInt(ARG_TITLE_RES)
        val msgRes = requireArguments().getInt(ARG_MSG_RES)
        val isSaveError = requireArguments().getBoolean(ARG_IS_SAVE_ERROR)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(titleRes))
            .setMessage(getString(msgRes))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.login_btn_retry)) { _, _ ->
                listener?.onRetryProfile(isSaveError)
            }
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                listener?.onSignOutFromErrorDialog()
            }
            .create()
    }
}

/**
 * 프로필 데이터 손상 오류 다이얼로그 (DialogFragment로 수명 관리).
 */
class ProfileCorruptedDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ProfileCorruptedDialogFragment"

        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_MSG_RES = "arg_msg_res"

        fun newInstance(titleResId: Int, messageResId: Int): ProfileCorruptedDialogFragment {
            return ProfileCorruptedDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, titleResId)
                    putInt(ARG_MSG_RES, messageResId)
                }
            }
        }
    }

    interface Listener {
        fun onSignOutFromCorruptedDialog()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val titleRes = requireArguments().getInt(ARG_TITLE_RES)
        val msgRes = requireArguments().getInt(ARG_MSG_RES)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(titleRes))
            .setMessage(getString(msgRes))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                listener?.onSignOutFromCorruptedDialog()
            }
            .create()
    }
}

/**
 * 회원가입 완료 안내 다이얼로그 (DialogFragment로 수명 관리).
 */
class SignUpCompleteDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "SignUpCompleteDialogFragment"

        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_MSG_RES = "arg_msg_res"

        fun newInstance(titleResId: Int, messageResId: Int): SignUpCompleteDialogFragment {
            return SignUpCompleteDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, titleResId)
                    putInt(ARG_MSG_RES, messageResId)
                }
            }
        }
    }

    interface Listener {
        fun onGetStartedFromSignUpComplete()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val titleRes = requireArguments().getInt(ARG_TITLE_RES)
        val msgRes = requireArguments().getInt(ARG_MSG_RES)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(titleRes))
            .setMessage(getString(msgRes))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.signup_btn_get_started)) { _, _ ->
                listener?.onGetStartedFromSignUpComplete()
            }
            .create()
    }
}
