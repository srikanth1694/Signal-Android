package org.thoughtcrime.securesms.longmessage;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.components.ConversationItemFooter;
import org.thoughtcrime.securesms.components.emoji.EmojiTextView;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DynamicDarkActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.views.Stub;

import static org.thoughtcrime.securesms.util.ThemeUtil.isDarkTheme;

public class LongMessageActivity extends PassphraseRequiredActivity {

  private static final String KEY_CONVERSATION_RECIPIENT = "recipient_id";
  private static final String KEY_MESSAGE_ID             = "message_id";
  private static final String KEY_IS_MMS                 = "is_mms";

  private static final int MAX_DISPLAY_LENGTH = 64 * 1024;

  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private final DynamicTheme    dynamicTheme    = new DynamicDarkActionBarTheme();

  private Stub<ViewGroup> sentBubble;
  private Stub<ViewGroup> receivedBubble;

  private LongMessageViewModel viewModel;

  public static Intent getIntent(@NonNull Context context, @NonNull RecipientId conversationRecipient, long messageId, boolean isMms) {
    Intent intent = new Intent(context, LongMessageActivity.class);
    intent.putExtra(KEY_CONVERSATION_RECIPIENT, conversationRecipient);
    intent.putExtra(KEY_MESSAGE_ID, messageId);
    intent.putExtra(KEY_IS_MMS, isMms);
    return intent;
  }

  @Override
  protected void onPreCreate() {
    super.onPreCreate();
    dynamicLanguage.onCreate(this);
    dynamicTheme.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    super.onCreate(savedInstanceState, ready);
    setContentView(R.layout.longmessage_activity);

    sentBubble     = new Stub<>(findViewById(R.id.longmessage_sent_stub));
    receivedBubble = new Stub<>(findViewById(R.id.longmessage_received_stub));

    initViewModel(getIntent().getLongExtra(KEY_MESSAGE_ID, -1), getIntent().getBooleanExtra(KEY_IS_MMS, false));

    LiveRecipient conversationRecipient = Recipient.live(getIntent().getParcelableExtra(KEY_CONVERSATION_RECIPIENT));
    conversationRecipient.observe(this, recipient -> updateActionBarColor(recipient.getColor()));
    updateActionBarColor(conversationRecipient.get().getColor());

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicLanguage.onResume(this);
    dynamicTheme.onResume(this);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home:
        finish();
        return true;
    }

    return false;
  }

  private void updateActionBarColor(@NonNull MaterialColor color) {
    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color.toActionBarColor(this)));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      getWindow().setStatusBarColor(color.toStatusBarColor(this));
    }
  }

  private void initViewModel(long messageId, boolean isMms) {
    viewModel = ViewModelProviders.of(this, new LongMessageViewModel.Factory(getApplication(), new LongMessageRepository(this), messageId, isMms))
                                  .get(LongMessageViewModel.class);

    viewModel.getMessage().observe(this, message -> {
      if (message == null) return;

      if (!message.isPresent()) {
        Toast.makeText(this, R.string.LongMessageActivity_unable_to_find_message, Toast.LENGTH_SHORT).show();
        finish();
        return;
      }


      if (message.get().getMessageRecord().isOutgoing()) {
        getSupportActionBar().setTitle(getString(R.string.LongMessageActivity_your_message));
      } else {
        Recipient recipient = message.get().getMessageRecord().getRecipient();
        String    name      = recipient.getDisplayName(this);
        getSupportActionBar().setTitle(getString(R.string.LongMessageActivity_message_from_s, name));
      }

      ViewGroup bubble;

      if (message.get().getMessageRecord().isOutgoing()) {
        bubble = sentBubble.get();
        bubble.getBackground().setColorFilter(ContextCompat.getColor(this, R.color.signal_background_secondary), PorterDuff.Mode.MULTIPLY);
      } else {
        bubble = receivedBubble.get();
        bubble.getBackground().setColorFilter(message.get().getMessageRecord().getRecipient().getColor().toConversationColor(this), PorterDuff.Mode.MULTIPLY);
      }

      EmojiTextView          text   = bubble.findViewById(R.id.longmessage_text);
      ConversationItemFooter footer = bubble.findViewById(R.id.longmessage_footer);

      CharSequence    trimmedBody = getTrimmedBody(message.get().getFullBody(this));
      SpannableString styledBody  = linkifyMessageBody(new SpannableString(trimmedBody));

      bubble.setVisibility(View.VISIBLE);
      text.setText(styledBody);
      text.setMovementMethod(LinkMovementMethod.getInstance());
      text.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSecurePreferences.getMessageBodyTextSize(this));
      if (message.get().getMessageRecord().isOutgoing()) {
        text.setMentionBackgroundTint(ContextCompat.getColor(this, isDarkTheme(this) ? R.color.core_grey_60 : R.color.core_grey_20));
      } else {
        text.setMentionBackgroundTint(ContextCompat.getColor(this, R.color.transparent_black_40));
      }
      footer.setMessageRecord(message.get().getMessageRecord(), dynamicLanguage.getCurrentLocale());
    });
  }

  private CharSequence getTrimmedBody(@NonNull CharSequence text) {
    return text.length() <= MAX_DISPLAY_LENGTH ? text
                                               : text.subSequence(0, MAX_DISPLAY_LENGTH);
  }

  private SpannableString linkifyMessageBody(SpannableString messageBody) {
    int     linkPattern = Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS;
    boolean hasLinks    = Linkify.addLinks(messageBody, linkPattern);

    if (hasLinks) {
      Stream.of(messageBody.getSpans(0, messageBody.length(), URLSpan.class))
            .filterNot(url -> LinkPreviewUtil.isLegalUrl(url.getURL()))
            .forEach(messageBody::removeSpan);
    }
    return messageBody;
  }
}
