import {Link} from 'react-router';
import DocPage, {DocSection, SupportLink} from './DocPage.jsx';
import StoreDownload from './StoreDownload.jsx';
import {MAX_DEVICES, MIN_ANDROID} from './lib/site.js';

/*
 * The Hebrew install guide, /he/install. It says what InstallPage.jsx says, step
 * for step, so change the two together; the section ids are the same, because
 * the app pages link to /he/install#xapk as they link to /install#xapk.
 *
 * Two kinds of label are handled differently. The Patched Store app is English
 * only, so its buttons are quoted in English (Start, Download, Install).
 * Android's and WhatsApp's own wording on a Hebrew-language phone varies by
 * maker and version, so most of it is described rather than quoted; the few
 * quoted Hebrew labels should be checked against a real phone.
 *
 * Paths like Android > media > com.whatsapp are folder names and stay in Latin
 * letters. They carry dir="ltr" so the arrows run the way the folders nest.
 */
function InstallPageHe() {
    return (
        <DocPage
            title="איך מתקינים"
            description="איך מתקינים אפליקציית אנדרואיד משופרת: מסירים את הגרסה מחנות Play, מאשרים את ההתקנה, ומשם אפליקציית החנות מטפלת בעדכונים."
            lead="אפליקציות משופרות מותקנות כמו כל אפליקציה מחוץ לחנות Play. בפעם הראשונה זה לוקח כמה דקות, ואחר כך אפליקציית החנות הופכת את זה ללחיצה אחת."
        >
            <nav aria-label="בדף הזה">
                <ul className="doc-toc">
                    <li><a href="#requirements">לפני שמתחילים</a></li>
                    <li><a href="#store-app">אפליקציית החנות</a></li>
                    <li><a href="#replace">החלפת הגרסה מחנות Play</a></li>
                    <li><a href="#whatsapp-chats">שמירת הצ&apos;אטים בוואטסאפ</a></li>
                    <li><a href="#play-protect">Play Protect</a></li>
                    <li><a href="#browser">התקנה מהדפדפן</a></li>
                    <li><a href="#xapk">חבילות XAPK</a></li>
                    <li><a href="#updates">עדכונים</a></li>
                    <li><a href="#troubleshooting">פתרון בעיות</a></li>
                </ul>
            </nav>

            <DocSection id="requirements" title="לפני שמתחילים">
                <ul>
                    <li>טלפון או טאבלט אנדרואיד עם <strong>{MIN_ANDROID} ומעלה</strong>.</li>
                    <li>
                        <Link to="/subscription" hrefLang="en">מנוי Pro</Link> כדי להוריד גרסאות משופרות.
                        אפליקציית החנות עצמה חינמית.
                    </li>
                    <li>קצת מקום פנוי: גרסה שוקלת בין 40 ל-150MB, והגרסה של וואטסאפ היא הגדולה מכולן.</li>
                </ul>
            </DocSection>

            <DocSection id="store-app" title="מתקינים את אפליקציית Patched Store (מומלץ)">
                <p>אפליקציית החנות היא הדרך הקלה ביותר להתקין גרסאות משופרות:</p>
                <ul>
                    <li>היא מתקינה חבילות מפוצלות (<strong>.xapk</strong>), שדפדפן לא יכול להתקין.</li>
                    <li>היא בודקת את החתימה של כל גרסה לפני ההתקנה, ומסרבת להתקין כל דבר שלא אנחנו חתמנו.</li>
                    <li>היא מעדכנת את עצמה.</li>
                </ul>
                <ol className="doc-steps">
                    <li>בטלפון, מקישים על <strong>הורדת APK</strong> למטה (הכפתור נמצא גם
                        ב<Link to="/he">דף הבית</Link>).</li>
                    <li>פותחים את הקובץ שהורד, מההתראה או מתיקיית ההורדות.</li>
                    <li>
                        אם אנדרואיד אומר שהדפדפן לא מורשה להתקין אפליקציות לא מוכרות, נכנסים להגדרות מהחלון
                        שנפתח, מפעילים את ההרשאה להתקין מהמקור הזה, חוזרים אחורה ומאשרים את ההתקנה.
                    </li>
                    <li>אם Google Play Protect מזהיר, ראו <a href="#play-protect">Play Protect</a> בהמשך.</li>
                    <li>
                        פותחים את Patched Store ומתחברים עם חשבון האתר. בוחרים אפליקציה וגרסה, ומקישים
                        על <strong>Download</strong> ואז על <strong>Install</strong>.
                    </li>
                </ol>
                <StoreDownload steps={false}/>
            </DocSection>

            <DocSection id="replace" title="החלפת הגרסה מחנות Play">
                <p>
                    אי אפשר להתקין אפליקציה משופרת מעל העותק מחנות Play. השתיים חתומות במפתחות שונים, ולכן
                    אנדרואיד מסרב להתקין (&quot;האפליקציה לא הותקנה&quot;, או
                    {' '}<code dir="ltr">INSTALL_FAILED_UPDATE_INCOMPATIBLE</code> באפליקציית החנות).
                    {' '}<strong>קודם מסירים את המקורית.</strong> אפליקציית החנות מזהה את המצב הזה ומציעה
                    {' '}<strong>Uninstall and retry</strong>.
                </p>
                <p className="doc-callout">
                    <strong>וואטסאפ: שומרים את הצ&apos;אטים לפני שמסירים.</strong> גיבוי ב-Google Drive לא
                    מספיק, והסרת האפליקציה מוחקת גם את הגיבוי שבטלפון. קודם עוברים
                    על <a href="#whatsapp-chats">שמירת הצ&apos;אטים בוואטסאפ</a>.
                </p>
                <p>
                    עושים את זה פעם אחת בלבד. כל הגרסאות המשופרות חתומות באותו מפתח, כך שגרסה חדשה מותקנת מעל
                    הישנה ושומרת על הנתונים.
                </p>
            </DocSection>

            <DocSection id="whatsapp-chats" title="שמירת הצ'אטים בוואטסאפ">
                <p className="doc-callout">
                    <strong>אפליקציית החנות עושה את זה בשבילכם.</strong> פותחים את וואטסאפ באפליקציית Patched
                    Store ומקישים על <strong>Start</strong> בכרטיס <strong>Keep your chats</strong>. היא מובילה
                    אתכם בכל שלב ומעבירה את הגיבוי בעצמה. השלבים שלמטה הם הדרך הידנית, למקרה שאי אפשר להשתמש
                    באפליקציית החנות.
                </p>
                <p>
                    <strong>אי אפשר לשחזר גיבוי Google Drive של וואטסאפ לגרסה משופרת.</strong> גוגל מאפשרת רק
                    לוואטסאפ המקורי להשתמש בו. במקום זה מעבירים את הצ&apos;אטים עם הגיבוי שוואטסאפ שומר בטלפון.
                    גם אותו הסרת וואטסאפ מוחקת, אז קודם מעתיקים אותו למקום בטוח.
                </p>
                <ol className="doc-steps">
                    <li>
                        בוואטסאפ מחנות Play, נכנסים ל<strong>הגדרות &gt; צ&apos;אטים &gt; גיבוי צ&apos;אטים</strong> ומקישים
                        על <strong>גיבוי</strong>. מלבד Google Drive, זה כותב עותק עדכני גם בטלפון.
                    </li>
                    <li>
                        אם <strong>גיבוי מוצפן מקצה לקצה</strong> מופעל במסך הזה, ודאו שאתם יודעים את הסיסמה או את
                        המפתח בן 64 הספרות. תצטרכו אותם כדי לשחזר.
                    </li>
                    <li>
                        פותחים מנהל קבצים, למשל <strong>Files by Google</strong>, ונכנסים
                        ל-<strong dir="ltr">Android &gt; media &gt; com.whatsapp</strong>. מעתיקים את
                        התיקייה <strong>WhatsApp</strong> שבתוכה אל <strong>Download</strong>, או למחשב.
                        בתיקייה <strong>Databases</strong> שלה נמצאים הצ&apos;אטים, וב-<strong>Media</strong> התמונות
                        והסרטונים.
                    </li>
                    <li>
                        מסירים את וואטסאפ של חנות Play, ואז מתקינים את הגרסה המשופרת עם אפליקציית החנות. עדיין לא
                        פותחים אותה.
                    </li>
                    <li>
                        מעתיקים את תיקיית <strong>WhatsApp</strong> השמורה בחזרה
                        אל <strong dir="ltr">Android &gt; media &gt; com.whatsapp</strong>, ויוצרים את
                        התיקייה <strong>com.whatsapp</strong> אם היא לא קיימת.
                    </li>
                    <li>
                        פותחים את וואטסאפ המשופר, מאמתים את <strong>אותו מספר טלפון</strong>, ומקישים
                        על <strong>שחזור</strong> כשהוא מוצא את הגיבוי.
                    </li>
                </ol>
                <p>
                    שומרים את העותק עד שמוודאים שהצ&apos;אטים חזרו. אם מנהל הקבצים בטלפון לא נותן לפתוח
                    את <strong dir="ltr">Android &gt; media</strong>, מחברים את הטלפון למחשב בכבל USB ומעתיקים משם
                    את אותה תיקייה. נתקעתם? כתבו לנו ל-<SupportLink/>.
                </p>
            </DocSection>

            <DocSection id="play-protect" title="Google Play Protect">
                <p>
                    הגרסאות שלנו חתומות במפתח שלנו, ש-Play Protect לא ראה קודם, ולכן הוא ממליץ לסרוק את האפליקציה
                    (או מציג אזהרה דומה) כשמתקינים אחת מהן. בוחרים להתקין בכל זאת, ובחלק מהטלפונים להתקין בלי
                    לסרוק. זו ההודעה הרגילה לכל אפליקציה מחוץ לחנות Play, והיא מופיעה בכל התקנה, כולל עדכונים.
                </p>
            </DocSection>

            <DocSection id="browser" title="התקנה מהדפדפן">
                <p>גרסאות שמסומנות <strong>APK</strong> בדף של אפליקציה אפשר להתקין גם בלי אפליקציית החנות:</p>
                <ol className="doc-steps">
                    <li>פותחים בטלפון את הדף של האפליקציה באתר ומקישים על <strong>הורדה</strong> בגרסה
                        הרצויה.</li>
                    <li>כשההורדה מסתיימת, פותחים את קובץ ה-<strong>.apk</strong>.</li>
                    <li>אם מתבקשים, מאשרים לדפדפן להתקין אפליקציות לא מוכרות, ואז מאשרים את ההתקנה.</li>
                </ol>
            </DocSection>

            <DocSection id="xapk" title="חבילות XAPK צריכות את אפליקציית החנות">
                <p>
                    גרסאות שמסומנות <strong>XAPK</strong> הן חבילות מפוצלות: כמה קובצי APK ארוזים בקובץ אחד.
                    אנדרואיד לא יכול להתקין קובץ כזה מהדפדפן או ממנהל קבצים. מורידים ומתקינים את הגרסאות האלה
                    עם <a href="#store-app">אפליקציית Patched Store</a>, שמתקינה את כל החלקים בבת אחת.
                </p>
            </DocSection>

            <DocSection id="updates" title="עדכונים">
                <ul>
                    <li>אפליקציית Patched Store מעדכנת את עצמה: כשיוצאת גרסה חדשה שלה, היא מציעה את העדכון בתוך
                        האפליקציה.</li>
                    <li>
                        אפליקציות משופרות לא מתעדכנות לבד. כשמופיעה גרסה חדשה, מורידים אותה ומתקינים מעל הקיימת.
                        הנתונים נשמרים, כי כל הגרסאות חתומות באותו מפתח.
                    </li>
                </ul>
            </DocSection>

            <DocSection id="troubleshooting" title="פתרון בעיות">
                <ul>
                    <li>
                        <strong>&quot;האפליקציה לא הותקנה&quot; או התנגשות עם חבילה קיימת:</strong> האפליקציה
                        המקורית עדיין מותקנת. מסירים אותה קודם (בוואטסאפ, <a href="#whatsapp-chats">שומרים את
                        הצ&apos;אטים</a> לפני כן).
                    </li>
                    <li>
                        <strong>שגיאה בניתוח החבילה:</strong> בטלפון יש גרסת אנדרואיד ישנה מ-{MIN_ANDROID}, או
                        שההורדה לא הסתיימה. מורידים שוב.
                    </li>
                    <li>
                        <strong>האפליקציה אומרת שהגעתם למגבלת המכשירים:</strong> כל אפליקציה משופרת בכל טלפון
                        תופסת אחד מ-{MAX_DEVICES} המקומות שלכם. אפשר לפנות אותם בדף
                        ה<Link to="/subscription" hrefLang="en">מנוי</Link>, פעם ב-30 יום.
                    </li>
                    <li>
                        <strong>עדיין תקועים?</strong> לחצו על <strong>דיווח על תקלה</strong> בתחתית כל דף, או כתבו
                        ל-<SupportLink/>.
                    </li>
                </ul>
            </DocSection>
        </DocPage>
    );
}

export default InstallPageHe;
