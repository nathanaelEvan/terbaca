package app.terbaca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurface
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary
import app.terbaca.core.ui.theme.TerbacaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TerbacaTheme {
                PlaceholderScreen()
            }
        }
    }
}

@Composable
fun PlaceholderScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TerbacaDarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = TerbacaDarkTextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.placeholder_tagline),
                fontSize = 16.sp,
                color = TerbacaDarkTextSecondary
            )
            Spacer(modifier = Modifier.height(28.dp))
            Surface(
                color = TerbacaDarkSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TerbacaDarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.placeholder_status),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.placeholder_ready),
                        fontSize = 12.sp,
                        color = TerbacaDarkTextSecondary
                    )
                }
            }
        }
    }
}
